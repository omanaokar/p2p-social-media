package com.proximity.core.messaging

import com.proximity.core.fromBase64
import com.proximity.core.identity.LocalIdentity
import com.proximity.core.randomBytes
import com.proximity.core.toBase64
import com.proximity.core.wire.SignedObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.time.Instant

/**
 * End-to-end encryption for direct messages.
 *
 * Key: HKDF-SHA256 over the X25519 shared secret of the sender's and
 * recipient's encryption keys. Cipher: ChaCha20-Poly1305 with a random nonce,
 * with sender, recipient and timestamp bound in as associated data.
 *
 * No forward secrecy: a leaked X25519 private key exposes past messages.
 * Acceptable for the course scope; a ratchet would fix it.
 */
class DmCrypto(private val identity: LocalIdentity) {

    /** The signed fields of a new message (without signature). */
    fun encrypt(toId: String, toEncryptionKey: String, text: String, image: ByteArray?, createdAt: Instant): JsonObject {
        val fromId = identity.currentUser.id
        val createdAtMs = createdAt.toEpochMilli()
        val nonce = randomBytes(NONCE_BYTES)
        val clear = kotlinx.serialization.json.Json.encodeToString(
            JsonObject.serializer(),
            JsonObject(buildMap {
                put("text", JsonPrimitive(text))
                image?.let { put("image", JsonPrimitive(it.toBase64())) }
            }),
        ).toByteArray()
        val sealed = cipher(true, key(toEncryptionKey, fromId, toId), nonce, aad(fromId, toId, createdAtMs), clear)
        return JsonObject(
            mapOf(
                "fromId" to JsonPrimitive(fromId),
                "toId" to JsonPrimitive(toId),
                "senderKey" to JsonPrimitive(identity.currentUser.encryptionKey),
                "nonce" to JsonPrimitive(nonce.toBase64()),
                "ciphertext" to JsonPrimitive(sealed.toBase64()),
                "createdAt" to JsonPrimitive(createdAtMs),
            ),
        )
    }

    /** Decrypts a message addressed to this user, or null if it was tampered with or not meant for us. */
    data class DecryptedMessage(val text: String, val image: ByteArray?)

    fun decrypt(dm: SignedObject): DecryptedMessage? {
        if (dm.str("toId") != identity.currentUser.id) return null
        return try {
            val fromId = dm.str("fromId")
            val toId = dm.str("toId")
            val clear = cipher(
                false,
                key(dm.str("senderKey"), fromId, toId),
                dm.str("nonce").fromBase64(),
                aad(fromId, toId, dm.long("createdAt")),
                dm.str("ciphertext").fromBase64(),
            )
            val parsed = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(String(clear)) as? JsonObject }.getOrNull()
            if (parsed == null || parsed["text"] == null) DecryptedMessage(String(clear), null) else DecryptedMessage(
                text = (parsed["text"] as? JsonPrimitive)?.content ?: "",
                image = (parsed["image"] as? JsonPrimitive)?.content?.fromBase64(),
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun key(remoteEncryptionKey: String, fromId: String, toId: String): ByteArray {
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(identity.sharedSecret(remoteEncryptionKey), SALT, "$fromId:$toId".toByteArray()))
        return ByteArray(32).also { hkdf.generateBytes(it, 0, it.size) }
    }

    private fun cipher(encrypt: Boolean, key: ByteArray, nonce: ByteArray, aad: ByteArray, input: ByteArray): ByteArray {
        val aead = ChaCha20Poly1305()
        aead.init(encrypt, AEADParameters(KeyParameter(key), TAG_BITS, nonce, aad))
        val out = ByteArray(aead.getOutputSize(input.size))
        val written = aead.processBytes(input, 0, input.size, out, 0)
        val total = written + aead.doFinal(out, written)
        return out.copyOf(total)
    }

    private fun aad(fromId: String, toId: String, createdAtMs: Long) = "$fromId|$toId|$createdAtMs".toByteArray()

    private companion object {
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
        val SALT = "proximity/dm/v1".toByteArray()
    }
}
