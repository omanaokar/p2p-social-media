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
import com.proximity.core.repo.SqliteInteractionEventLedger
import com.proximity.core.store.ObjectStore
import com.proximity.core.store.toPost
import com.proximity.core.sync.PeerTable
import com.proximity.core.sync.SyncEngine
import com.proximity.contracts.RankContext
import com.proximity.contracts.RankedPost
import com.proximity.ranker.RecommendationSystem
import com.proximity.ranker.classifier.HeuristicTextClassifier
import com.proximity.ranker.classifier.TextContentClassifier
import com.proximity.ranker.moderation.ContentModerator
import com.proximity.ranker.moderation.DefaultContentModerator
import com.proximity.ranker.profile.FileUserInterestProfileStore
import com.proximity.ranker.recommendation.PeerRecommendation
import com.proximity.ranker.recommendation.PeerRecommendationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File
import java.nio.file.Files

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
    /** Device-local educational feed ranking, learning, and peer recommendation services. */
    val recommendationSystem: RecommendationSystem,
    /** Null when no [MediaEngineFactory] was supplied (calling is a stretch goal). */
    val calls: CallService?,
    private val db: AppDatabase,
    private val sync: SyncEngine,
    private val scope: CoroutineScope,
    private val clock: Clock,
) {
    /** Short alias for [recommendationSystem]. */
    val recommendations: RecommendationSystem get() = recommendationSystem

    /** Starts discovery and syncing. */
    suspend fun start() = sync.start()

    suspend fun stop() = sync.stop()

    /** Runs TTL cleanup now instead of waiting for the timer. */
    fun runCleanup() = sync.runCleanup()

    /**
     * Ranked feed candidates held by this device. Abusive, profane and adult posts are filtered out;
     * educational posts are boosted when relevant but are not required.
     * The post repository owns candidate construction and the recommendation system owns ranking.
     */
    fun watchRankedEducationalFeed(): Flow<List<RankedPost>> =
        posts.watchFeedCandidates().map { candidates ->
            recommendationSystem.rankFeed(candidates, RankContext(clock.now(), identity.currentUser.id))
        }

    /**
     * Recommends peers using reaction data stored locally. Friendship and blocked-user exclusions
     * remain caller-supplied because this application has no separate friendship graph.
     */
    suspend fun recommendPeers(context: PeerRecommendationContext): List<PeerRecommendation> =
        recommendationSystem.recommendPeers(
            context.copy(
                myLikedPostIds = reactions.getReactedPostIds(identity.currentUser.id),
                peerLikedPostIds = reactions.getPeerReactedPostIds(),
            )
        )

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
            classifier: TextContentClassifier = HeuristicTextClassifier(),
            moderator: ContentModerator = DefaultContentModerator(classifier = classifier),
        ): AppCore {
            // One thread for sync so peer messages are handled in order; the
            // database lock covers repository calls from other threads.
            val io = Dispatchers.IO.limitedParallelism(1)
            val scope = CoroutineScope(SupervisorJob() + io)
            val identity = LocalIdentity.load(keyStore)
            val db = AppDatabase.open(driver, databasePath)
            val crypto = DmCrypto(identity)
            val store = ObjectStore(db, identity, crypto, config, clock, classifier, moderator)
            val peers = PeerTable()
            val sync = SyncEngine(transport, store, identity, peers, config, clock, scope)
            val profiles = ProfileRepositoryImpl(db, store, sync, peers, identity, clock, io)
            profiles.ensureProfile()
            val profileFile = recommendationProfilePath(databasePath, identity.currentUser.id)
            val recommendations = RecommendationSystem(
                profileStore = FileUserInterestProfileStore(profileFile),
                classifier = classifier,
                moderator = moderator,
                interactionEventLedger = SqliteInteractionEventLedger(db, io, identity.currentUser.id),
                postResolver = { postId ->
                    db.query("SELECT * FROM posts WHERE id = ?", postId).firstOrNull()?.toPost()
                },
            )
            return AppCore(
                identity = identity,
                posts = PostRepositoryImpl(db, store, sync, identity, config, clock, io, classifier, moderator),
                comments = CommentRepositoryImpl(db, store, sync, identity, clock, io, recommendations),
                reactions = ReactionRepositoryImpl(db, store, sync, identity, clock, io, recommendations),
                messages = MessageRepositoryImpl(db, store, sync, identity, crypto, clock, io),
                profiles = profiles,
                recommendationSystem = recommendations,
                calls = mediaEngineFactory?.let { CallServiceImpl(sync, peers, identity, it, config, clock, scope) },
                db = db,
                sync = sync,
                scope = scope,
                clock = clock,
            )
        }

        private fun recommendationProfilePath(databasePath: String?, userId: String): File {
            if (databasePath == null) {
                // In-memory databases are used by tests/demos; keep their profile isolated while
                // retaining persistence for the lifetime of a reopened core given an explicit path.
                return Files.createTempFile("proximity-interest-", ".profile").toFile()
            }
            val databaseFile = File(databasePath)
            val directory = databaseFile.parentFile ?: File(".")
            val legacy = File(directory, "${databaseFile.name}.interests")
            val scoped = File(directory, "${databaseFile.name}.interests.$userId")
            migrateLegacyProfile(legacy, scoped, userId)
            return scoped
        }

        /** Claims an unscoped legacy profile for the first identity that opens this database. */
        private fun migrateLegacyProfile(legacy: File, scoped: File, userId: String) {
            if (!legacy.exists() || scoped.exists()) return
            val owner = File("${legacy.path}.owner")
            val ownsLegacy = try {
                if (owner.exists()) owner.readText().trim() == userId
                else {
                    owner.parentFile?.mkdirs()
                    java.nio.file.Files.writeString(owner.toPath(), userId, java.nio.file.StandardOpenOption.CREATE_NEW)
                    true
                }
            } catch (_: java.nio.file.FileAlreadyExistsException) {
                owner.readText().trim() == userId
            }
            if (ownsLegacy) {
                val temporary = java.nio.file.Files.createTempFile(scoped.parentFile.toPath(), ".${scoped.name}.", ".tmp")
                try {
                    java.nio.file.Files.copy(legacy.toPath(), temporary, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    java.nio.file.Files.move(temporary, scoped.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                } catch (_: java.nio.file.FileAlreadyExistsException) {
                    // Another initialization for the same owner completed the copy first.
                } finally {
                    java.nio.file.Files.deleteIfExists(temporary)
                }
            }
        }
    }
}
