package com.proximity.core.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Messages exchanged over a transport link. Encoded as JSON.
 *
 * Sync flow on every new connection:
 *  - both sides send [Hello] (own profile + ids of everything forwardable),
 *  - each side answers with [Want] for ids it lacks,
 *  - the other side replies with [Objects].
 *
 * Newly stored objects are announced to the other connected peers with
 * [Have], which triggers the same want/objects exchange, so posts spread in
 * waves.
 */
sealed class SyncMessage {
    protected abstract fun body(): Map<String, JsonElement>

    fun encode(): ByteArray =
        Json.encodeToString(JsonElement.serializer(), JsonObject(mapOf("v" to JsonPrimitive(PROTOCOL_VERSION)) + body()))
            .toByteArray()

    class Hello(val profile: SignedObject, val ids: List<String>) : SyncMessage() {
        override fun body() = mapOf("t" to JsonPrimitive("hello"), "profile" to profile.toJson(), "ids" to ids.toJson())
    }

    class Have(val ids: List<String>) : SyncMessage() {
        override fun body() = mapOf("t" to JsonPrimitive("have"), "ids" to ids.toJson())
    }

    class Want(val ids: List<String>) : SyncMessage() {
        override fun body() = mapOf("t" to JsonPrimitive("want"), "ids" to ids.toJson())
    }

    class Objects(val objects: List<SignedObject>) : SyncMessage() {
        override fun body() = mapOf("t" to JsonPrimitive("objs"), "objs" to JsonArray(objects.map { it.toJson() }))
    }

    /** Call signalling. Signed by the sender so a peer cannot impersonate someone else on the link. */
    class CallSignal(val fields: JsonObject, val signature: String) : SyncMessage() {
        override fun body() = mapOf("t" to JsonPrimitive("call"), "fields" to fields, "sig" to JsonPrimitive(signature))
    }

    companion object {
        const val PROTOCOL_VERSION = 1

        /** Throws [IllegalArgumentException] for anything that is not a valid message. */
        fun decode(bytes: ByteArray): SyncMessage {
            val json = try {
                Json.parseToJsonElement(String(bytes)) as? JsonObject
            } catch (e: Exception) {
                null
            }
            require(json != null && (json["v"] as? JsonPrimitive)?.intOrNull == PROTOCOL_VERSION) { "unknown protocol version" }
            return when (json["t"]?.primitiveString()) {
                "hello" -> Hello(SignedObject.fromJson(json["profile"]), ids(json["ids"]))
                "have" -> Have(ids(json["ids"]))
                "want" -> Want(ids(json["ids"]))
                "objs" -> {
                    val list = json["objs"] as? JsonArray ?: throw IllegalArgumentException("objs must be a list")
                    Objects(list.map(SignedObject::fromJson))
                }
                "call" -> {
                    val fields = json["fields"] as? JsonObject
                    val sig = json["sig"]?.primitiveString()
                    require(fields != null && sig != null) { "malformed call signal" }
                    CallSignal(fields, sig)
                }
                else -> throw IllegalArgumentException("unknown message type")
            }
        }

        private fun ids(value: JsonElement?): List<String> {
            val array = value as? JsonArray ?: throw IllegalArgumentException("ids must be a list")
            return array.map { it.primitiveString() ?: throw IllegalArgumentException("ids must be strings") }
        }

        private fun List<String>.toJson() = JsonArray(map(::JsonPrimitive))
    }
}
