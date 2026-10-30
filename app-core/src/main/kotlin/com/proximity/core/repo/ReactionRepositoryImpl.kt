package com.proximity.core.repo

import com.proximity.contracts.IdentityService
import com.proximity.contracts.Reaction
import com.proximity.contracts.ReactionRepository
import com.proximity.contracts.ReactionSummary
import com.proximity.core.Clock
import com.proximity.core.db.AppDatabase
import com.proximity.core.db.long
import com.proximity.core.db.str
import com.proximity.core.store.ObjectStore
import com.proximity.core.store.toReaction
import com.proximity.core.sync.SyncEngine
import com.proximity.core.wire.ObjectKind
import com.proximity.core.wire.SignedObject
import com.proximity.ranker.RecommendationSystem
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

class ReactionRepositoryImpl(
    private val db: AppDatabase,
    private val store: ObjectStore,
    private val sync: SyncEngine,
    private val identity: IdentityService,
    private val clock: Clock,
    private val io: CoroutineDispatcher,
    private val recommendations: RecommendationSystem? = null,
) : ReactionRepository {

    override suspend fun add(targetId: String, type: String): Reaction {
        require(type != Reaction.REMOVED) { "Use remove() to clear a reaction" }
        return publish(targetId, type)
    }

    override suspend fun remove(targetId: String): Reaction = publish(targetId, Reaction.REMOVED)

    private suspend fun publish(targetId: String, type: String): Reaction = withContext(io) {
        val me = identity.currentUser.id
        val postId = postIdFor(targetId)
        // Latest wins by timestamp, so a new reaction must be strictly newer
        // than our previous one even if the clock has not moved.
        val previous = db.query("SELECT created_at FROM reactions WHERE target_id = ? AND author_id = ?", targetId, me)
            .firstOrNull()?.long("created_at")
        val createdAt = maxOf(clock.now().toEpochMilli(), (previous ?: Long.MIN_VALUE) + 1)
        val obj = SignedObject.create(
            ObjectKind.REACTION,
            mapOf("targetId" to targetId, "postId" to postId, "authorId" to me, "type" to type, "createdAt" to createdAt),
            identity,
        )
        store.saveLocal(obj)
        sync.publish(obj)
        val reaction = db.query("SELECT * FROM reactions WHERE id = ?", obj.id).first().toReaction()
        if (type != Reaction.REMOVED) {
            recommendations?.recordFeedInteraction(postId = postId, type = com.proximity.contracts.InteractionType.REACT, eventId = obj.id)
        }
        reaction
    }

    private fun postIdFor(targetId: String): String {
        if (db.query("SELECT 1 FROM posts WHERE id = ?", targetId).isNotEmpty()) return targetId
        return db.query("SELECT post_id FROM comments WHERE id = ?", targetId).firstOrNull()?.str("post_id")
            ?: throw IllegalStateException("Nothing with id $targetId on this device")
    }

    override fun watchForPost(postId: String): Flow<Map<String, ReactionSummary>> = db.watch(setOf("reactions", "blocked")) {
        val me = identity.currentUser.id
        val counts = mutableMapOf<String, MutableMap<String, Int>>()
        val mine = mutableMapOf<String, String>()
        for (row in db.query(
            """SELECT target_id, author_id, type FROM reactions
               WHERE post_id = ? AND type != ? AND author_id NOT IN (SELECT user_id FROM blocked)""",
            postId, Reaction.REMOVED,
        )) {
            counts.getOrPut(row.str("target_id")) { mutableMapOf() }.merge(row.str("type"), 1, Int::plus)
            if (row.str("author_id") == me) mine[row.str("target_id")] = row.str("type")
        }
        (counts.keys + mine.keys).associateWith { ReactionSummary(counts[it].orEmpty(), mine[it]) }
    }.flowOn(io)

    override suspend fun getReactedPostIds(userId: String): Set<String> = withContext(io) {
        db.query(
            """SELECT DISTINCT post_id FROM reactions
               WHERE author_id = ? AND type != ?
                 AND author_id NOT IN (SELECT user_id FROM blocked)""",
            userId, Reaction.REMOVED,
        ).map { it.str("post_id") }.toSet()
    }

    override suspend fun getPeerReactedPostIds(): Map<String, Set<String>> = withContext(io) {
        val me = identity.currentUser.id
        val rows = db.query(
            """SELECT author_id, post_id FROM reactions
               WHERE author_id != ? AND type != ?
                 AND author_id NOT IN (SELECT user_id FROM blocked)""",
            me, Reaction.REMOVED,
        )
        val result = mutableMapOf<String, MutableSet<String>>()
        for (row in rows) {
            result.getOrPut(row.str("author_id")) { mutableSetOf() }.add(row.str("post_id"))
        }
        result
    }
}
