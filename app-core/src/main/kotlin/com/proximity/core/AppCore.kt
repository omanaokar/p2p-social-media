package com.proximity.core

import androidx.sqlite.SQLiteDriver
import com.proximity.contracts.CallService
import com.proximity.contracts.CommentRepository
import com.proximity.contracts.IdentityService
import com.proximity.contracts.MessageRepository
import com.proximity.contracts.PostRepository
import com.proximity.contracts.ProfileRepository
import com.proximity.contracts.ReactionRepository
import com.proximity.contracts.Transport
import com.proximity.core.calls.CallServiceImpl
import com.proximity.core.calls.MediaEngineFactory
import com.proximity.core.db.AppDatabase
import com.proximity.core.identity.KeyStore
import com.proximity.core.identity.LocalIdentity
import com.proximity.core.messaging.DmCrypto
import com.proximity.core.repo.CommentRepositoryImpl
import com.proximity.core.repo.MessageRepositoryImpl
import com.proximity.core.repo.PostRepositoryImpl
import com.proximity.core.repo.ProfileRepositoryImpl
import com.proximity.core.repo.ReactionRepositoryImpl
import com.proximity.core.store.ObjectStore
import com.proximity.core.sync.PeerTable
import com.proximity.core.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Wires the application layer together. The UI gets everything it needs from
 * here:
 *
 * ```kotlin
 * val core = AppCore.open(transport, keyStore, BundledSQLiteDriver(), context.getDatabasePath("proximity.db").path)
 * core.start()
 * core.posts.watchFeedCandidates().collect { ... }
 * ```
 */
class AppCore private constructor(
    val identity: IdentityService,
    val posts: PostRepository,
    val comments: CommentRepository,
    val reactions: ReactionRepository,
    val messages: MessageRepository,
    val profiles: ProfileRepository,
    /** Null when no [MediaEngineFactory] was supplied (calling is a stretch goal). */
    val calls: CallService?,
    private val db: AppDatabase,
    private val sync: SyncEngine,
    private val scope: CoroutineScope,
) {
    /** Starts discovery and syncing. */
    suspend fun start() = sync.start()

    suspend fun stop() = sync.stop()

    /** Runs TTL cleanup now instead of waiting for the timer. */
    fun runCleanup() = sync.runCleanup()

    suspend fun close() {
        sync.stop()
        scope.cancel()
        db.close()
    }

    companion object {
        /** [databasePath] null opens an in-memory database (tests, demos). */
        @OptIn(ExperimentalCoroutinesApi::class)
        suspend fun open(
            transport: Transport,
            keyStore: KeyStore,
            driver: SQLiteDriver,
            databasePath: String?,
            config: AppConfig = AppConfig(),
            clock: Clock = Clock.SYSTEM,
            mediaEngineFactory: MediaEngineFactory? = null,
        ): AppCore {
            // One thread for sync so peer messages are handled in order; the
            // database lock covers repository calls from other threads.
            val io = Dispatchers.IO.limitedParallelism(1)
            val scope = CoroutineScope(SupervisorJob() + io)
            val identity = LocalIdentity.load(keyStore)
            val db = AppDatabase.open(driver, databasePath)
            val crypto = DmCrypto(identity)
            val store = ObjectStore(db, identity, crypto, config, clock)
            val peers = PeerTable()
            val sync = SyncEngine(transport, store, identity, peers, config, clock, scope)
            val profiles = ProfileRepositoryImpl(db, store, sync, peers, identity, clock, io)
            profiles.ensureProfile()
            return AppCore(
                identity = identity,
                posts = PostRepositoryImpl(db, store, sync, identity, config, clock, io),
                comments = CommentRepositoryImpl(db, store, sync, identity, clock, io),
                reactions = ReactionRepositoryImpl(db, store, sync, identity, clock, io),
                messages = MessageRepositoryImpl(db, store, sync, identity, crypto, clock, io),
                profiles = profiles,
                calls = mediaEngineFactory?.let { CallServiceImpl(sync, peers, identity, it, config, clock, scope) },
                db = db,
                sync = sync,
                scope = scope,
            )
        }
    }
}
