package com.proximity.core

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.proximity.core.calls.MediaEngineFactory
import com.proximity.core.identity.InMemoryKeyStore
import com.proximity.core.identity.KeyStore
import com.proximity.core.testing.InMemoryNetwork
import com.proximity.core.testing.InMemoryTransport
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.time.Duration
import java.time.Instant

class FakeClock(@Volatile var now: Instant = Instant.parse("2026-10-22T12:00:00Z")) : Clock {
    override fun now(): Instant = now
    fun advance(d: Duration) {
        now = now.plus(d)
    }
}

class Node(val name: String, val transport: InMemoryTransport, val core: AppCore) {
    val id: String get() = core.identity.currentUser.id
}

class Harness(val config: AppConfig = AppConfig(), val clock: FakeClock = FakeClock()) {
    val network = InMemoryNetwork()
    private val nodes = mutableListOf<Node>()

    suspend fun node(
        name: String,
        keyStore: KeyStore = InMemoryKeyStore(),
        databasePath: String? = null,
        media: MediaEngineFactory? = null,
        start: Boolean = true,
    ): Node {
        val transport = network.createTransport(name)
        val core = AppCore.open(transport, keyStore, BundledSQLiteDriver(), databasePath, config, clock, media)
        if (start) core.start()
        return Node(name, transport, core).also { nodes += it }
    }

    fun connect(a: Node, b: Node, signal: Int? = null) = network.connect(a.transport, b.transport, signal)
    fun disconnect(a: Node, b: Node) = network.disconnect(a.transport, b.transport)

    suspend fun closeAll() = nodes.forEach { it.core.close() }
}

/** Waits until the flow emits a value matching [predicate]. */
suspend fun <T> Flow<T>.awaitMatching(timeoutMs: Long = 5_000, predicate: (T) -> Boolean): T =
    withTimeout(timeoutMs) { first(predicate) }

/** Gives in-flight sync messages time to settle before asserting something did not happen. */
suspend fun settle() = delay(300)
