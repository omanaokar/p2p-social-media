package com.proximity.core.store

import com.proximity.contracts.IdentityService
import com.proximity.contracts.MessageStatus
import com.proximity.core.AppConfig
import com.proximity.core.Clock
import com.proximity.core.db.AppDatabase
import com.proximity.core.db.int
import com.proximity.core.db.long
import com.proximity.core.db.str
import com.proximity.core.fromBase64
import com.proximity.core.messaging.DmCrypto
import com.proximity.core.wire.ObjectKind
import com.proximity.core.wire.SignedObject
import java.time.Instant

class IngestResult private constructor(
    val stored: Boolean,
    val reason: String?,
    /** Objects this device created in response (a delivery receipt). Already stored; announce them. */
    val created: List<SignedObject>,
) {
    companion object {
        fun stored(created: List<SignedObject> = emptyList()) = IngestResult(true, null, created)
        fun skipped(reason: String) = IngestResult(false, reason, emptyList())
    }
}

/**
 * All reads and writes of synced objects. Locally created and received
 * objects both go through here, so the same rules apply to both.
 */
class ObjectStore(
    val db: AppDatabase,
    private val identity: IdentityService,
    private val crypto: DmCrypto,
    private val config: AppConfig,
    private val clock: Clock,
) {
    private val guard = SpamGuard(config)
    private val me: String get() = identity.currentUser.id

    // ------------------------------------------------------------- writes

    /**
     * Stores an object created on this device. Throws [IllegalArgumentException]
     * if it breaks a limit peers would enforce, since it would never spread.
     */
    fun saveLocal(obj: SignedObject, dmBody: String? = null, dmImage: ByteArray? = null) {
        val now = clock.now()
        val problem = guard.checkContent(obj, now) ?: guard.checkRate(obj.kind, recentCount(obj, now))
        require(problem == null) { problem!!.reason }
        val result = store(obj, hopCount = 0, now = now, dmBody = dmBody, dmImage = dmImage)
        check(result.stored) { "Could not store ${obj.kind.wireName}: ${result.reason}" }
    }

    /** Validates and stores an object received from a peer. */
    fun ingest(obj: SignedObject, fromUserId: String? = null, signal: Int? = null): IngestResult {
        if (isKnown(obj.id)) return IngestResult.skipped("duplicate")
        val hopCount = obj.hopCount + 1
        val hopLimit = if (obj.kind == ObjectKind.DM || obj.kind == ObjectKind.RECEIPT) config.maxDmHopCount else config.maxPostHopCount
        if (hopCount > hopLimit) return IngestResult.skipped("hop limit")
        if (!obj.verify(identity)) return IngestResult.skipped("bad signature")

        val now = clock.now()
        val problem = (if (isBlocked(obj.signerId)) Rejection("blocked author") else null)
            ?: guard.checkContent(obj, now)
            ?: guard.checkRate(obj.kind, recentCount(obj, now))
        if (problem != null) {
            if (problem.permanent) tombstone(obj.id, now)
            return IngestResult.skipped(problem.reason)
        }
        return store(obj, hopCount, now, fromUserId, signal)
    }

    private fun store(
        obj: SignedObject,
        hopCount: Int,
        now: Instant,
        fromUserId: String? = null,
        signal: Int? = null,
        dmBody: String? = null,
        dmImage: ByteArray? = null,
    ): IngestResult {
        val result = when (obj.kind) {
            ObjectKind.PROFILE -> storeProfile(obj, hopCount, now)
            ObjectKind.POST -> storePost(obj, hopCount, now, fromUserId, signal)
            ObjectKind.COMMENT -> storeComment(obj, hopCount, now)
            ObjectKind.REACTION -> storeReaction(obj, hopCount, now)
            ObjectKind.DM -> storeDm(obj, hopCount, now, dmBody, dmImage)
            ObjectKind.RECEIPT -> storeReceipt(obj, hopCount, now)
        }
        if (result.stored) {
            // A receipt updates or deletes the message it confirms.
            val extra = if (obj.kind == ObjectKind.RECEIPT) setOf(ObjectKind.DM.table) else emptySet()
            db.notify(setOf(obj.kind.table) + extra + result.created.map { it.kind.table })
        }
        return result
    }

    private fun storeProfile(obj: SignedObject, hopCount: Int, now: Instant): IngestResult {
        val existing = db.query("SELECT id, updated_at FROM profiles WHERE public_key = ?", obj.signerId).firstOrNull()
        val updatedAt = obj.long("updatedAt")
        if (existing != null && existing.long("updated_at") >= updatedAt) {
            tombstone(obj.id, now)
            return IngestResult.skipped("older profile")
        }
        db.transaction {
            if (existing != null) tombstone(existing.str("id"), now)
            db.execute(
                """INSERT INTO profiles (public_key, id, encryption_key, display_name, avatar, updated_at, hop_count, signature, raw)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                   ON CONFLICT (public_key) DO UPDATE SET id = excluded.id, encryption_key = excluded.encryption_key,
                     display_name = excluded.display_name, avatar = excluded.avatar, updated_at = excluded.updated_at,
                     hop_count = excluded.hop_count, signature = excluded.signature, raw = excluded.raw""",
                obj.signerId, obj.id, obj.str("encryptionKey"), obj.optStr("displayName"), obj.optStr("avatar")?.fromBase64(),
                updatedAt, hopCount, obj.signature, obj.encodeRaw(),
            )
        }
        return IngestResult.stored()
    }

    private fun storePost(obj: SignedObject, hopCount: Int, now: Instant, fromUserId: String?, signal: Int?): IngestResult {
        val createdAt = obj.long("createdAt")
        val ttl = obj.long("ttl")
        db.execute(
            """INSERT OR IGNORE INTO posts (id, author_id, body, image, created_at, ttl, expires_at, hop_count, signature,
                 received_at, received_from, received_signal, raw)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            obj.id, obj.signerId, obj.str("body"), obj.optStr("image")?.fromBase64(), createdAt, ttl, createdAt + ttl,
            hopCount, obj.signature, now.toEpochMilli(), fromUserId, signal, obj.encodeRaw(),
        )
        return IngestResult.stored()
    }

    private fun storeComment(obj: SignedObject, hopCount: Int, now: Instant): IngestResult {
        // Not tombstoned: the post may still arrive, and then so can the comment.
        if (!postExists(obj.str("postId"))) return IngestResult.skipped("unknown post")
        db.execute(
            """INSERT OR IGNORE INTO comments (id, post_id, author_id, body, created_at, hop_count, signature, received_at, raw)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            obj.id, obj.str("postId"), obj.signerId, obj.str("body"), obj.long("createdAt"), hopCount, obj.signature,
            now.toEpochMilli(), obj.encodeRaw(),
        )
        return IngestResult.stored()
    }

    private fun storeReaction(obj: SignedObject, hopCount: Int, now: Instant): IngestResult {
        val targetId = obj.str("targetId")
        val postId = obj.str("postId")
        if (!postExists(postId)) return IngestResult.skipped("unknown post")
        if (targetId != postId && db.query("SELECT 1 FROM comments WHERE id = ? AND post_id = ?", targetId, postId).isEmpty()) {
            return IngestResult.skipped("unknown comment")
        }
        val existing = db.query(
            "SELECT id, created_at FROM reactions WHERE target_id = ? AND author_id = ?", targetId, obj.signerId,
        ).firstOrNull()
        if (existing != null && existing.long("created_at") >= obj.long("createdAt")) {
            tombstone(obj.id, now)
            return IngestResult.skipped("older reaction")
        }
        db.transaction {
            if (existing != null) {
                db.execute("DELETE FROM reactions WHERE id = ?", existing.str("id"))
                tombstone(existing.str("id"), now)
            }
            db.execute(
                """INSERT INTO reactions (id, target_id, post_id, author_id, type, created_at, hop_count, signature, raw)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                obj.id, targetId, postId, obj.signerId, obj.str("type"), obj.long("createdAt"), hopCount, obj.signature,
                obj.encodeRaw(),
            )
        }
        return IngestResult.stored()
    }

    private fun storeDm(obj: SignedObject, hopCount: Int, now: Instant, localBody: String?, localImage: ByteArray?): IngestResult {
        val fromId = obj.signerId
        val toId = obj.str("toId")
        var created = emptyList<SignedObject>()
        val status: String
        var body: String? = null
        var image: ByteArray? = null
        when {
            fromId == me && localBody != null -> {
                status = MessageStatus.PENDING.name
                body = localBody
                image = localImage
            }
            toId == me -> {
                val decrypted = crypto.decrypt(obj) ?: run {
                    tombstone(obj.id, now)
                    return IngestResult.skipped("undecryptable")
                }
                body = decrypted.text
                image = decrypted.image
                status = MessageStatus.RECEIVED.name
                created = listOf(
                    SignedObject.create(
                        ObjectKind.RECEIPT,
                        mapOf("messageId" to obj.id, "fromId" to me, "createdAt" to now.toEpochMilli()),
                        identity,
                    ),
                )
            }
            else -> {
                // Carrying someone else's message. Drop it if its recipient already confirmed delivery.
                if (db.query("SELECT 1 FROM receipts WHERE message_id = ? AND from_id = ?", obj.id, toId).isNotEmpty()) {
                    tombstone(obj.id, now)
                    return IngestResult.skipped("already delivered")
                }
                status = STATUS_RELAY
            }
        }
        db.transaction {
            db.execute(
                """INSERT OR IGNORE INTO direct_messages (id, from_id, to_id, created_at, expires_at, hop_count, raw, body, status, image)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                obj.id, fromId, toId, obj.long("createdAt"), obj.timestamp.plus(config.dmTtl).toEpochMilli(), hopCount,
                obj.encodeRaw(), body, status, image,
            )
            created.forEach { insertReceipt(it, 0) }
        }
        return IngestResult.stored(created)
    }

    private fun storeReceipt(obj: SignedObject, hopCount: Int, now: Instant): IngestResult {
        val messageId = obj.str("messageId")
        val message = db.query("SELECT to_id, status FROM direct_messages WHERE id = ?", messageId).firstOrNull()
        if (message != null && message.str("to_id") != obj.signerId) {
            tombstone(obj.id, now)
            return IngestResult.skipped("receipt not from recipient")
        }
        db.transaction {
            insertReceipt(obj, hopCount)
            when (message?.str("status")) {
                MessageStatus.PENDING.name ->
                    db.execute("UPDATE direct_messages SET status = ? WHERE id = ?", MessageStatus.DELIVERED.name, messageId)
                STATUS_RELAY -> {
                    db.execute("DELETE FROM direct_messages WHERE id = ?", messageId)
                    tombstone(messageId, now)
                }
            }
        }
        return IngestResult.stored()
    }

    private fun insertReceipt(obj: SignedObject, hopCount: Int) {
        db.execute(
            """INSERT OR IGNORE INTO receipts (id, message_id, from_id, created_at, expires_at, hop_count, raw)
               VALUES (?, ?, ?, ?, ?, ?, ?)""",
            obj.id, obj.str("messageId"), obj.signerId, obj.long("createdAt"),
            obj.timestamp.plus(config.dmTtl).toEpochMilli(), hopCount, obj.encodeRaw(),
        )
    }

    /** Removes a post and everything attached to it, and never accepts it again. */
    fun deletePost(postId: String) {
        db.transaction {
            db.execute("DELETE FROM posts WHERE id = ?", postId)
            deleteOrphans()
            tombstone(postId, clock.now())
        }
        db.notify(setOf("posts", "comments", "reactions"))
    }

    fun block(userId: String) {
        require(userId != me) { "Cannot block yourself" }
        db.transaction {
            db.execute("INSERT OR IGNORE INTO blocked (user_id, created_at) VALUES (?, ?)", userId, clock.now().toEpochMilli())
            db.execute("DELETE FROM posts WHERE author_id = ?", userId)
            db.execute("DELETE FROM comments WHERE author_id = ?", userId)
            db.execute("DELETE FROM reactions WHERE author_id = ?", userId)
            db.execute("DELETE FROM direct_messages WHERE from_id = ? AND status != ?", userId, MessageStatus.PENDING.name)
            deleteOrphans()
        }
        db.notify(setOf("blocked", "posts", "comments", "reactions", "direct_messages"))
    }

    fun unblock(userId: String) {
        db.execute("DELETE FROM blocked WHERE user_id = ?", userId)
        db.notify(setOf("blocked"))
    }

    /**
     * Records when a nearby peer was seen. Deliberately does not notify: signal
     * updates are frequent and the nearby list is driven by the peer table.
     */
    fun touchPeer(userId: String, signal: Int?) {
        db.execute(
            "UPDATE profiles SET last_seen_at = ?, last_signal = COALESCE(?, last_signal) WHERE public_key = ?",
            clock.now().toEpochMilli(), signal, userId,
        )
    }

    /**
     * TTL cleanup. Messages to or from this user are kept as history; they
     * simply stop being forwarded.
     */
    fun cleanup() {
        val now = clock.now().toEpochMilli()
        val tombstoneRetention = maxOf(config.maxPostTtl, config.dmTtl).multipliedBy(2).toMillis()
        db.transaction {
            db.execute("DELETE FROM posts WHERE expires_at <= ?", now)
            deleteOrphans()
            db.execute("DELETE FROM direct_messages WHERE status = ? AND expires_at <= ?", STATUS_RELAY, now)
            db.execute("DELETE FROM receipts WHERE expires_at <= ?", now)
            db.execute("DELETE FROM tombstones WHERE created_at <= ?", now - tombstoneRetention)
            db.execute(
                """DELETE FROM profiles
                   WHERE public_key != ?1
                     AND COALESCE(last_seen_at, updated_at) <= ?2
                     AND public_key NOT IN (SELECT author_id FROM posts UNION SELECT author_id FROM comments)
                     AND public_key NOT IN (SELECT from_id FROM direct_messages WHERE status != ?3
                                            UNION SELECT to_id FROM direct_messages WHERE status != ?3)""",
                me, now - config.profileRetention.toMillis(), STATUS_RELAY,
            )
        }
        db.notify(setOf("posts", "comments", "reactions", "direct_messages", "receipts", "profiles"))
    }

    private fun deleteOrphans() {
        db.execute("DELETE FROM comments WHERE post_id NOT IN (SELECT id FROM posts)")
        db.execute("DELETE FROM reactions WHERE post_id NOT IN (SELECT id FROM posts)")
    }

    private fun tombstone(id: String, now: Instant) =
        db.execute("INSERT OR IGNORE INTO tombstones (id, created_at) VALUES (?, ?)", id, now.toEpochMilli())

    // -------------------------------------------------------------- reads

    fun isKnown(id: String): Boolean = db.query(
        """SELECT EXISTS(SELECT 1 FROM posts WHERE id = ?1)
             OR EXISTS(SELECT 1 FROM comments WHERE id = ?1)
             OR EXISTS(SELECT 1 FROM reactions WHERE id = ?1)
             OR EXISTS(SELECT 1 FROM profiles WHERE id = ?1)
             OR EXISTS(SELECT 1 FROM direct_messages WHERE id = ?1)
             OR EXISTS(SELECT 1 FROM receipts WHERE id = ?1)
             OR EXISTS(SELECT 1 FROM tombstones WHERE id = ?1) AS known""",
        id,
    ).first().long("known") == 1L

    fun unknownIds(ids: Collection<String>): List<String> = ids.distinct().filterNot(::isKnown)

    fun isBlocked(userId: String): Boolean = db.query("SELECT 1 FROM blocked WHERE user_id = ?", userId).isNotEmpty()

    /** Ids of everything this device would hand to a peer: the sync digest. */
    fun forwardableIds(): List<String> = db.query(
        """SELECT id FROM profiles WHERE hop_count <= ?2
           UNION ALL SELECT id FROM posts WHERE expires_at > ?1 AND hop_count <= ?2
           UNION ALL SELECT c.id FROM comments c JOIN posts p ON p.id = c.post_id
             WHERE p.expires_at > ?1 AND c.hop_count <= ?2
           UNION ALL SELECT r.id FROM reactions r JOIN posts p ON p.id = r.post_id
             WHERE p.expires_at > ?1 AND r.hop_count <= ?2
           UNION ALL SELECT id FROM direct_messages
             WHERE expires_at > ?1 AND hop_count <= ?3 AND status IN (?4, ?5)
           UNION ALL SELECT id FROM receipts WHERE expires_at > ?1 AND hop_count <= ?3""",
        clock.now().toEpochMilli(), config.maxRelayHops, config.dmMaxRelayHops, MessageStatus.PENDING.name, STATUS_RELAY,
    ).map { it.str("id") }

    /** Whether a freshly stored object should be announced onwards. */
    fun isForwardable(obj: SignedObject, storedHopCount: Int): Boolean = when (obj.kind) {
        ObjectKind.DM -> storedHopCount <= config.dmMaxRelayHops && obj.str("toId") != me
        ObjectKind.RECEIPT -> storedHopCount <= config.dmMaxRelayHops
        else -> storedHopCount <= config.maxRelayHops
    }

    /** Objects by id, ordered so dependencies come first. */
    fun getObjects(ids: Collection<String>): List<SignedObject> {
        val wanted = ids.distinct()
        return ObjectKind.entries.flatMap { kind ->
            wanted.chunked(500).flatMap { chunk ->
                db.query(
                    "SELECT raw, hop_count FROM ${kind.table} WHERE id IN (${chunk.joinToString { "?" }})",
                    *chunk.toTypedArray(),
                ).map { SignedObject.decodeRaw(it.str("raw"), it.int("hop_count")) }
            }
        }
    }

    fun profileObject(userId: String): SignedObject? =
        db.query("SELECT raw, hop_count FROM profiles WHERE public_key = ?", userId).firstOrNull()
            ?.let { SignedObject.decodeRaw(it.str("raw"), it.int("hop_count")) }

    private fun postExists(postId: String) = db.query("SELECT 1 FROM posts WHERE id = ?", postId).isNotEmpty()

    private fun recentCount(obj: SignedObject, now: Instant): Int {
        val since = now.minus(config.rateLimitWindow).toEpochMilli()
        val rows = when (obj.kind) {
            ObjectKind.POST ->
                db.query("SELECT COUNT(*) AS n FROM posts WHERE author_id = ? AND created_at > ?", obj.signerId, since)
            ObjectKind.COMMENT, ObjectKind.REACTION, ObjectKind.DM -> db.query(
                """SELECT (SELECT COUNT(*) FROM comments WHERE author_id = ?1 AND created_at > ?2)
                        + (SELECT COUNT(*) FROM reactions WHERE author_id = ?1 AND created_at > ?2)
                        + (SELECT COUNT(*) FROM direct_messages WHERE from_id = ?1 AND created_at > ?2) AS n""",
                obj.signerId, since,
            )
            ObjectKind.PROFILE, ObjectKind.RECEIPT -> return 0
        }
        return rows.first().int("n")
    }

    companion object {
        const val STATUS_RELAY = "RELAY"
    }
}
