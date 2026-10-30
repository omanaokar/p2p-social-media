package com.proximity.core.wire

import com.proximity.contracts.IdentityService
import com.proximity.core.canonicalJson
import com.proximity.core.sha256Hex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import java.time.Instant

/**
 * Everything that travels between phones. Declared in the order objects must
 * be stored in, so a batch never holds a comment before its post.
 */
enum class ObjectKind(val wireName: String, val table: String, internal val signerField: String) {
    PROFILE("profile", "profiles", "publicKey"),
    POST("post", "posts", "authorId"),
    COMMENT("comment", "comments", "authorId"),
    REACTION("reaction", "reactions", "authorId"),
    DM("dm", "direct_messages", "fromId"),
    RECEIPT("receipt", "receipts", "fromId");

    companion object {
        fun fromWire(name: String?): ObjectKind? = entries.firstOrNull { it.wireName == name }
    }
}

/**
 * The one wire format for every synced object.
 *
 * [fields] is the signed content. [id] is the SHA-256 of the canonical JSON of
 * kind + fields, and [signature] is the signer's Ed25519 signature over those
 * same bytes. [hopCount] is not signed because every relay bumps it.
 */
class SignedObject private constructor(
    val kind: ObjectKind,
    val fields: JsonObject,
    val id: String,
    val signature: String,
    val hopCount: Int,
) {
    val signerId: String get() = str(kind.signerField)

    /** Creation time, or update time for profiles. */
    val timestamp: Instant get() = Instant.ofEpochMilli((fields["createdAt"] ?: fields["updatedAt"])!!.jsonPrimitive.long)

    fun str(key: String): String = fields.getValue(key).jsonPrimitive.content
    fun optStr(key: String): String? = fields[key]?.jsonPrimitive?.contentOrNull
    fun long(key: String): Long = fields.getValue(key).jsonPrimitive.long

    /** True if [id] matches the content and the signer really signed it. */
    fun verify(identity: IdentityService): Boolean {
        val bytes = canonical(kind, fields)
        return sha256Hex(bytes) == id && identity.verify(bytes, signature, signerId)
    }

    fun withHopCount(hopCount: Int) = SignedObject(kind, fields, id, signature, hopCount)

    fun toJson(): JsonObject = JsonObject(
        mapOf(
            "kind" to JsonPrimitive(kind.wireName),
            "id" to JsonPrimitive(id),
            "sig" to JsonPrimitive(signature),
            "hop" to JsonPrimitive(hopCount),
            "fields" to fields,
        ),
    )

    /** Storage form: the envelope without the hop count, which lives in its own column. */
    fun encodeRaw(): String = Json.encodeToString(JsonElement.serializer(), JsonObject(toJson() - "hop"))

    companion object {
        private val requiredStrings = mapOf(
            ObjectKind.PROFILE to listOf("publicKey", "encryptionKey"),
            ObjectKind.POST to listOf("authorId", "body"),
            ObjectKind.COMMENT to listOf("postId", "authorId", "body"),
            ObjectKind.REACTION to listOf("targetId", "postId", "authorId", "type"),
            ObjectKind.DM to listOf("fromId", "toId", "senderKey", "nonce", "ciphertext"),
            ObjectKind.RECEIPT to listOf("messageId", "fromId"),
        )
        private val requiredLongs = mapOf(
            ObjectKind.PROFILE to listOf("updatedAt"),
            ObjectKind.POST to listOf("createdAt", "ttl"),
            ObjectKind.COMMENT to listOf("createdAt"),
            ObjectKind.REACTION to listOf("createdAt"),
            ObjectKind.DM to listOf("createdAt"),
            ObjectKind.RECEIPT to listOf("createdAt"),
        )
        private val optionalStrings = mapOf(
            ObjectKind.PROFILE to listOf("displayName", "avatar"),
            ObjectKind.POST to listOf("image"),
        )

        private fun canonical(kind: ObjectKind, fields: JsonObject) =
            canonicalJson(JsonObject(mapOf("kind" to JsonPrimitive(kind.wireName), "fields" to fields)))

        /** Signs new content. Values may be String, Long, Int or null (dropped). */
        fun create(kind: ObjectKind, fields: Map<String, Any?>, identity: IdentityService): SignedObject {
            val json = JsonObject(
                fields.mapNotNull { (key, value) ->
                    when (value) {
                        null -> null
                        is String -> key to JsonPrimitive(value)
                        is Number -> key to JsonPrimitive(value.toLong())
                        is JsonPrimitive -> if (value is JsonNull) null else key to value
                        else -> throw IllegalArgumentException("Unsupported field type for $key")
                    }
                }.toMap(),
            )
            checkShape(kind, json)
            val bytes = canonical(kind, json)
            return SignedObject(kind, json, sha256Hex(bytes), identity.sign(bytes), 0)
        }

        /**
         * Parses an object received from a peer. Throws [IllegalArgumentException]
         * if the shape is wrong; authenticity is checked separately with [verify].
         */
        fun fromJson(json: JsonElement?): SignedObject {
            val obj = json as? JsonObject ?: throw IllegalArgumentException("object is not a map")
            val kind = ObjectKind.fromWire(obj["kind"]?.primitiveString())
            val id = obj["id"]?.primitiveString()
            val sig = obj["sig"]?.primitiveString()
            val hop = obj["hop"]?.let { (it as? JsonPrimitive)?.intOrNull } ?: 0
            val fields = obj["fields"] as? JsonObject
            require(kind != null && id != null && sig != null && fields != null && hop >= 0) { "malformed object envelope" }
            checkShape(kind, fields)
            return SignedObject(kind, fields, id, sig, hop)
        }

        fun decodeRaw(raw: String, hopCount: Int): SignedObject =
            fromJson(Json.parseToJsonElement(raw).jsonObject).withHopCount(hopCount)

        private fun checkShape(kind: ObjectKind, fields: JsonObject) {
            for (key in requiredStrings.getValue(kind)) {
                require(fields[key]?.primitiveString() != null) { "${kind.wireName}.$key must be a string" }
            }
            for (key in requiredLongs.getValue(kind)) {
                val value = fields[key] as? JsonPrimitive
                require(value != null && !value.isString && value.longOrNull != null) { "${kind.wireName}.$key must be an integer" }
            }
            for (key in optionalStrings[kind].orEmpty()) {
                val value = fields[key]
                require(value == null || value is JsonNull || value.primitiveString() != null) {
                    "${kind.wireName}.$key must be a string"
                }
            }
        }
    }
}

internal fun JsonElement.primitiveString(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
