package com.proximity.core.repo

import com.proximity.contracts.ChatMessage
import com.proximity.contracts.Conversation
import com.proximity.contracts.IdentityService
import com.proximity.contracts.MessageRepository
import com.proximity.core.Clock
import com.proximity.core.db.AppDatabase
import com.proximity.core.db.str
import com.proximity.core.messaging.DmCrypto
import com.proximity.core.store.ObjectStore
import com.proximity.core.store.toChatMessage
import com.proximity.core.store.toProfile
import com.proximity.core.sync.SyncEngine
import com.proximity.core.wire.ObjectKind
import com.proximity.core.wire.SignedObject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Direct messages travel through the same gossip sync as posts, encrypted so
 * only the recipient can read them. Any peer may carry one; if the recipient
 * is not nearby it waits on carriers' phones until an encounter or expiry.
 */
class MessageRepositoryImpl(
    private val db: AppDatabase,
    private val store: ObjectStore,
    private val sync: SyncEngine,
    private val identity: IdentityService,
    private val crypto: DmCrypto,
    private val clock: Clock,
    private val io: CoroutineDispatcher,
) : MessageRepository {

    override suspend fun send(toUserId: String, text: String): ChatMessage = withContext(io) {
        require(text.isNotBlank()) { "Message is empty" }
        require(toUserId != identity.currentUser.id) { "Cannot message yourself" }
        val recipientKey = db.query("SELECT encryption_key FROM profiles WHERE public_key = ?", toUserId).firstOrNull()
            ?.str("encryption_key")
            ?: throw IllegalStateException("No profile for $toUserId yet; meet them first")

        val fields = crypto.encrypt(toUserId, recipientKey, text, clock.now())
        val obj = SignedObject.create(ObjectKind.DM, fields, identity)
        store.saveLocal(obj, dmBody = text)
        sync.publish(obj)
        db.query("SELECT * FROM direct_messages WHERE id = ?", obj.id).first().toChatMessage()
    }

    override fun watchConversation(peerUserId: String): Flow<List<ChatMessage>> = db.watch(setOf("direct_messages")) {
        db.query(
            """SELECT * FROM direct_messages
               WHERE status != ?3
                 AND ((from_id = ?1 AND to_id = ?2) OR (from_id = ?2 AND to_id = ?1))
               ORDER BY created_at""",
            identity.currentUser.id, peerUserId, ObjectStore.STATUS_RELAY,
        ).map { it.toChatMessage() }
    }.flowOn(io)

    override fun watchInbox(): Flow<List<Conversation>> = db.watch(setOf("direct_messages", "profiles")) {
        val me = identity.currentUser.id
        val latest = LinkedHashMap<String, ChatMessage>()
        for (row in db.query("SELECT * FROM direct_messages WHERE status != ? ORDER BY created_at DESC", ObjectStore.STATUS_RELAY)) {
            val message = row.toChatMessage()
            latest.putIfAbsent(if (message.fromId == me) message.toId else message.fromId, message)
        }
        if (latest.isEmpty()) return@watch emptyList()
        val profiles = db.query("SELECT * FROM profiles").associate { it.str("public_key") to it.toProfile() }
        latest.map { (peer, message) -> Conversation(peer, profiles[peer], message) }
    }.flowOn(io)
}
