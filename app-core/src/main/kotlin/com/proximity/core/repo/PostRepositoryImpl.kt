package com.proximity.core.repo

import com.proximity.contracts.FeedCandidate
import com.proximity.contracts.IdentityService
import com.proximity.contracts.Post
import com.proximity.contracts.PostRepository
import com.proximity.contracts.Reaction
import com.proximity.core.AppConfig
import com.proximity.core.Clock
import com.proximity.core.db.AppDatabase
import com.proximity.core.db.int
import com.proximity.core.db.long
import com.proximity.core.db.optInt
import com.proximity.core.db.optStr
import com.proximity.core.db.str
import com.proximity.core.store.ObjectStore
import com.proximity.core.store.toPost
import com.proximity.core.store.toProfile
import com.proximity.core.sync.SyncEngine
import com.proximity.core.toBase64
import com.proximity.core.wire.ObjectKind
import com.proximity.core.wire.SignedObject
import com.proximity.ranker.classifier.HeuristicTextClassifier
import com.proximity.ranker.classifier.TextContentClassifier
import com.proximity.ranker.moderation.ContentModerator
import com.proximity.ranker.moderation.DefaultContentModerator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.time.Instant

class PostRepositoryImpl(
    private val db: AppDatabase,
    private val store: ObjectStore,
    private val sync: SyncEngine,
    private val identity: IdentityService,
    private val config: AppConfig,
    private val clock: Clock,
    private val io: CoroutineDispatcher,
    private val classifier: TextContentClassifier = store.classifier,
    private val moderator: ContentModerator = store.moderator,
) : PostRepository {

    override suspend fun create(body: String, image: ByteArray?): Post = withContext(io) {
        require(body.isNotBlank() || image != null) { "Post is empty" }
        val classification = classifier.classify(body)
        if (config.strictEducationFilter) {
            require(classification.isEducational) {
                "Only educational posts can be published. Found ${classification.category}: ${classification.summary}"
            }
        }
        // Abusive, profane, hateful, threatening and adult posts are always blocked.
        val decision = moderator.decide(classification)
        require(decision.isApproved) {
            if (config.strictEducationFilter) {
                "Only educational posts can be published. Moderation rejected: ${decision.reason}"
            } else {
                "Post blocked: ${decision.reason}"
            }
        }
        val obj = SignedObject.create(
            ObjectKind.POST,
            mapOf(
                "authorId" to identity.currentUser.id,
                "body" to body,
                "image" to image?.toBase64(),
                "createdAt" to clock.now().toEpochMilli(),
                "ttl" to config.postTtl.toMillis(),
            ),
            identity,
        )
        store.saveLocal(obj)
        sync.publish(obj)
        db.query("SELECT * FROM posts WHERE id = ?", obj.id).first().toPost()
    }

    override fun watchFeedCandidates(): Flow<List<FeedCandidate>> =
        db.watch(setOf("posts", "comments", "reactions", "profiles", "blocked")) { feedCandidates() }.flowOn(io)

    private fun feedCandidates(): List<FeedCandidate> {
        val me = identity.currentUser.id
        val posts = db.query(
            """SELECT p.*, (SELECT COUNT(*) FROM comments c WHERE c.post_id = p.id) AS comment_count
               FROM posts p
               WHERE p.expires_at > ? AND p.author_id NOT IN (SELECT user_id FROM blocked)
               ORDER BY p.created_at DESC""",
            clock.now().toEpochMilli(),
        )
        if (posts.isEmpty()) return emptyList()

        val counts = mutableMapOf<String, MutableMap<String, Int>>()
        val mine = mutableMapOf<String, String>()
        for (row in db.query("SELECT target_id, author_id, type FROM reactions WHERE target_id = post_id AND type != ?", Reaction.REMOVED)) {
            val perType = counts.getOrPut(row.str("target_id")) { mutableMapOf() }
            perType.merge(row.str("type"), 1, Int::plus)
            if (row.str("author_id") == me) mine[row.str("target_id")] = row.str("type")
        }
        val profiles = db.query("SELECT * FROM profiles").associate { it.str("public_key") to it.toProfile() }

        val candidates = posts.map { row ->
            FeedCandidate(
                post = row.toPost(),
                author = profiles[row.str("author_id")],
                receivedAt = Instant.ofEpochMilli(row.long("received_at")),
                receivedFrom = row.optStr("received_from"),
                receivedSignalStrength = row.optInt("received_signal"),
                commentCount = row.int("comment_count"),
                reactionCounts = counts[row.str("id")].orEmpty(),
                myReaction = mine[row.str("id")],
                isMine = row.str("author_id") == me,
            )
        }

        return candidates.filter { candidate ->
            val classification = classifier.classify(candidate.post.body)
            (!config.strictEducationFilter || classification.isEducational) &&
                moderator.decide(classification).isApproved
        }
    }

    override suspend fun delete(postId: String) = withContext(io) { store.deletePost(postId) }

    override suspend fun report(postId: String, blockAuthor: Boolean) = withContext(io) {
        val author = db.query("SELECT author_id FROM posts WHERE id = ?", postId).firstOrNull()?.str("author_id")
        store.deletePost(postId)
        if (blockAuthor && author != null && author != identity.currentUser.id) store.block(author)
    }
}
