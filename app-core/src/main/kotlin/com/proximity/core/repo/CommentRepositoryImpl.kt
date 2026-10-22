package com.proximity.core.repo

import com.proximity.contracts.Comment
import com.proximity.contracts.CommentRepository
import com.proximity.contracts.CommentView
import com.proximity.contracts.IdentityService
import com.proximity.core.Clock
import com.proximity.core.db.AppDatabase
import com.proximity.core.db.str
import com.proximity.core.store.ObjectStore
import com.proximity.core.store.toComment
import com.proximity.core.store.toProfile
import com.proximity.core.sync.SyncEngine
import com.proximity.core.wire.ObjectKind
import com.proximity.core.wire.SignedObject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

class CommentRepositoryImpl(
    private val db: AppDatabase,
    private val store: ObjectStore,
    private val sync: SyncEngine,
    private val identity: IdentityService,
    private val clock: Clock,
    private val io: CoroutineDispatcher,
) : CommentRepository {

    override suspend fun add(postId: String, body: String): Comment = withContext(io) {
        require(body.isNotBlank()) { "Comment is empty" }
        check(db.query("SELECT 1 FROM posts WHERE id = ?", postId).isNotEmpty()) { "Post $postId is not on this device" }
        val obj = SignedObject.create(
            ObjectKind.COMMENT,
            mapOf(
                "postId" to postId,
                "authorId" to identity.currentUser.id,
                "body" to body,
                "createdAt" to clock.now().toEpochMilli(),
            ),
            identity,
        )
        store.saveLocal(obj)
        sync.publish(obj)
        db.query("SELECT * FROM comments WHERE id = ?", obj.id).first().toComment()
    }

    override fun watchForPost(postId: String): Flow<List<CommentView>> = db.watch(setOf("comments", "profiles", "blocked")) {
        val me = identity.currentUser.id
        val profiles = db.query(
            "SELECT * FROM profiles WHERE public_key IN (SELECT author_id FROM comments WHERE post_id = ?)", postId,
        ).associate { it.str("public_key") to it.toProfile() }
        db.query(
            """SELECT * FROM comments
               WHERE post_id = ? AND author_id NOT IN (SELECT user_id FROM blocked)
               ORDER BY created_at""",
            postId,
        ).map { row -> CommentView(row.toComment(), profiles[row.str("author_id")], row.str("author_id") == me) }
    }.flowOn(io)
}
