package com.proximity.core.store

import com.proximity.core.AppConfig
import com.proximity.core.wire.ObjectKind
import com.proximity.core.wire.SignedObject
import java.time.Duration
import java.time.Instant

/** Why an object was refused. Permanent rejections are tombstoned so the object is never fetched again. */
data class Rejection(val reason: String, val permanent: Boolean = true)

/**
 * Size limits, sanity checks and per-author rate limits. There is no server to
 * moderate, so every phone applies the same rules to what it accepts and
 * forwards.
 */
class SpamGuard(private val config: AppConfig) {

    fun checkContent(obj: SignedObject, now: Instant): Rejection? {
        if (obj.timestamp.isAfter(now.plus(config.maxClockSkew))) return Rejection("dated in the future", permanent = false)
        if (!HEX_KEY.matches(obj.signerId)) return Rejection("malformed signer id")
        return when (obj.kind) {
            ObjectKind.PROFILE -> when {
                !HEX_KEY.matches(obj.str("encryptionKey")) -> Rejection("malformed encryption key")
                (obj.optStr("displayName")?.length ?: 0) > config.maxDisplayNameChars -> Rejection("display name too long")
                base64Bytes(obj.optStr("avatar")) > config.maxAvatarBytes -> Rejection("avatar too large")
                else -> null
            }
            ObjectKind.POST -> {
                val ttl = obj.long("ttl")
                when {
                    obj.str("body").length > config.maxPostChars -> Rejection("post too long")
                    base64Bytes(obj.optStr("image")) > config.maxImageBytes -> Rejection("image too large")
                    ttl <= 0 || ttl > config.maxPostTtl.toMillis() -> Rejection("invalid ttl")
                    !obj.timestamp.plusMillis(ttl).isAfter(now) -> Rejection("expired", permanent = false)
                    else -> null
                }
            }
            ObjectKind.COMMENT ->
                if (obj.str("body").length > config.maxCommentChars) Rejection("comment too long") else null
            ObjectKind.REACTION ->
                if (obj.str("type").let { it.isEmpty() || it.length > 32 }) Rejection("invalid reaction type") else null
            ObjectKind.DM -> when {
                // Ciphertext + 16 byte tag; allow 4 UTF-8 bytes per char.
                base64Bytes(obj.str("ciphertext")) > config.maxMessageChars * 4 + 16 -> Rejection("message too long")
                !HEX_KEY.matches(obj.str("toId")) || !HEX_KEY.matches(obj.str("senderKey")) -> Rejection("malformed message keys")
                expired(obj, config.dmTtl, now) -> Rejection("expired", permanent = false)
                else -> null
            }
            ObjectKind.RECEIPT -> if (expired(obj, config.dmTtl, now)) Rejection("expired", permanent = false) else null
        }
    }

    /**
     * [recentCount] is how many objects of the same class this author created
     * within [AppConfig.rateLimitWindow], as seen by this device.
     */
    fun checkRate(kind: ObjectKind, recentCount: Int): Rejection? = when (kind) {
        ObjectKind.POST -> if (recentCount >= config.maxPostsPerWindow) Rejection("post rate limit") else null
        ObjectKind.COMMENT, ObjectKind.REACTION, ObjectKind.DM ->
            if (recentCount >= config.maxOtherPerWindow) Rejection("rate limit") else null
        ObjectKind.PROFILE, ObjectKind.RECEIPT -> null
    }

    private fun expired(obj: SignedObject, ttl: Duration, now: Instant) = !obj.timestamp.plus(ttl).isAfter(now)

    private fun base64Bytes(value: String?) = if (value == null) 0 else value.length * 3 / 4

    private companion object {
        val HEX_KEY = Regex("^[0-9a-f]{64}$")
    }
}
