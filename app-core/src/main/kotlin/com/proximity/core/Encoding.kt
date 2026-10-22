package com.proximity.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

internal fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "odd-length hex" }
    return ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}

internal fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

internal fun ByteArray.toBase64(): String = Base64.getEncoder().encodeToString(this)

internal fun String.fromBase64(): ByteArray = Base64.getDecoder().decode(this)

private val secureRandom = SecureRandom()

internal fun randomBytes(size: Int): ByteArray = ByteArray(size).also(secureRandom::nextBytes)

/**
 * JSON with keys sorted at every level, so the same content always produces
 * the same bytes. Hashes and signatures are computed over this.
 */
internal fun canonicalJson(value: JsonObject): ByteArray = Json.encodeToString(JsonElement.serializer(), sorted(value)).toByteArray()

private fun sorted(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> JsonObject(value.entries.sortedBy { it.key }.associate { it.key to sorted(it.value) })
    is JsonArray -> JsonArray(value.map(::sorted))
    else -> value
}
