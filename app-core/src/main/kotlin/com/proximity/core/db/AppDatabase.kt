package com.proximity.core.db

import androidx.sqlite.SQLITE_DATA_BLOB
import androidx.sqlite.SQLITE_DATA_FLOAT
import androidx.sqlite.SQLITE_DATA_INTEGER
import androidx.sqlite.SQLITE_DATA_NULL
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

typealias Row = Map<String, Any?>

/**
 * The device's local SQLite database. The only source of truth is what this
 * phone has seen.
 *
 * Writers call [notify] with the tables they touched; [watch] re-runs its
 * query whenever one of its tables changes. One connection guarded by a lock:
 * plenty for the data sizes a proximity feed produces. Call from a background
 * dispatcher, never the main thread.
 */
class AppDatabase private constructor(private val connection: SQLiteConnection) {
    private val lock = ReentrantLock()
    private val changes = MutableSharedFlow<Set<String>>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        query("PRAGMA journal_mode = WAL")
        execute("PRAGMA foreign_keys = ON")
        migrate()
    }

    fun query(sql: String, vararg args: Any?): List<Row> = lock.withLock {
        connection.prepare(sql).use { statement ->
            statement.bindAll(args)
            val rows = mutableListOf<Row>()
            while (statement.step()) {
                rows += (0 until statement.getColumnCount()).associate { i -> statement.getColumnName(i) to statement.value(i) }
            }
            rows
        }
    }

    fun execute(sql: String, vararg args: Any?): Unit = lock.withLock {
        connection.prepare(sql).use { statement ->
            statement.bindAll(args)
            while (statement.step()) Unit
        }
    }

    fun <T> transaction(body: () -> T): T = lock.withLock {
        execute("BEGIN IMMEDIATE")
        try {
            body().also { execute("COMMIT") }
        } catch (e: Throwable) {
            execute("ROLLBACK")
            throw e
        }
    }

    fun notify(tables: Set<String>) {
        if (tables.isNotEmpty()) changes.tryEmit(tables)
    }

    /** Emits [query]'s result now and again whenever one of [tables] changes or [extraTrigger] fires. */
    fun <T> watch(tables: Set<String>, extraTrigger: Flow<Unit> = emptyFlow(), query: () -> T): Flow<T> = channelFlow {
        // Subscribe to every trigger before the first query so no change in
        // between is missed. (merge() would subscribe later, on dispatch.)
        launch(start = CoroutineStart.UNDISPATCHED) {
            changes.collect { changed -> if (changed.any(tables::contains)) send(query()) }
        }
        launch(start = CoroutineStart.UNDISPATCHED) { extraTrigger.collect { send(query()) } }
        send(query())
    }.conflate()

    fun close() = lock.withLock { connection.close() }

    private fun migrate() {
        val from = (query("PRAGMA user_version").first().values.first() as Long).toInt()
        check(from <= Schema.migrations.size) { "Database schema v$from is newer than this app (v${Schema.migrations.size})" }
        for (version in from until Schema.migrations.size) {
            transaction {
                Schema.migrations[version].forEach { execute(it) }
                execute("PRAGMA user_version = ${version + 1}")
            }
        }
    }

    companion object {
        /** [path] null opens an in-memory database (tests, demos). */
        fun open(driver: SQLiteDriver, path: String?): AppDatabase = AppDatabase(driver.open(path ?: ":memory:"))

        private fun SQLiteStatement.bindAll(args: Array<out Any?>) {
            args.forEachIndexed { i, arg ->
                val index = i + 1
                when (arg) {
                    null -> bindNull(index)
                    is String -> bindText(index, arg)
                    is Long -> bindLong(index, arg)
                    is Int -> bindLong(index, arg.toLong())
                    is Boolean -> bindLong(index, if (arg) 1 else 0)
                    is ByteArray -> bindBlob(index, arg)
                    is Double -> bindDouble(index, arg)
                    else -> throw IllegalArgumentException("Cannot bind ${arg::class}")
                }
            }
        }

        private fun SQLiteStatement.value(i: Int): Any? = when (getColumnType(i)) {
            SQLITE_DATA_NULL -> null
            SQLITE_DATA_INTEGER -> getLong(i)
            SQLITE_DATA_FLOAT -> getDouble(i)
            SQLITE_DATA_BLOB -> getBlob(i)
            else -> getText(i)
        }
    }
}

fun Row.str(key: String): String = get(key) as String
fun Row.optStr(key: String): String? = get(key) as String?
fun Row.long(key: String): Long = get(key) as Long
fun Row.optLong(key: String): Long? = get(key) as Long?
fun Row.int(key: String): Int = (get(key) as Long).toInt()
fun Row.optInt(key: String): Int? = (get(key) as Long?)?.toInt()
fun Row.blob(key: String): ByteArray? = get(key) as ByteArray?
