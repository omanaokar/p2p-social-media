package com.proximity.core.identity

import com.proximity.contracts.IdentityService
import com.proximity.contracts.LocalUser
import com.proximity.core.fromBase64
import com.proximity.core.hexToBytes
import com.proximity.core.randomBytes
import com.proximity.core.toBase64
import com.proximity.core.toHex
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * The device owner's identity: an Ed25519 key for signing (its public key is
 * the user id) and an X25519 key for direct message encryption. Both are
 * generated on first launch and never leave the [KeyStore].
 */
class LocalIdentity private constructor(
    private val signingKey: Ed25519PrivateKeyParameters,
    private val encryptionKey: X25519PrivateKeyParameters,
) : IdentityService {

    override val currentUser = LocalUser(
        id = signingKey.generatePublicKey().encoded.toHex(),
        encryptionKey = encryptionKey.generatePublicKey().encoded.toHex(),
    )

    override fun sign(data: ByteArray): String {
        val signer = Ed25519Signer()
        signer.init(true, signingKey)
        signer.update(data, 0, data.size)
        return signer.generateSignature().toBase64()
    }

    override fun verify(data: ByteArray, signature: String, authorId: String): Boolean = try {
        val signer = Ed25519Signer()
        signer.init(false, Ed25519PublicKeyParameters(authorId.hexToBytes(), 0))
        signer.update(data, 0, data.size)
        signer.verifySignature(signature.fromBase64())
    } catch (e: Exception) {
        // Malformed key or signature encoding.
        false
    }

    /**
     * X25519 shared secret with another user's encryption key. Both sides of a
     * conversation derive the same value.
     */
    fun sharedSecret(remoteEncryptionKey: String): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(encryptionKey)
        val out = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(X25519PublicKeyParameters(remoteEncryptionKey.hexToBytes(), 0), out, 0)
        return out
    }

    companion object {
        private const val SIGNING_SEED = "identity.ed25519.seed"
        private const val ENCRYPTION_SEED = "identity.x25519.seed"

        fun load(store: KeyStore): LocalIdentity = LocalIdentity(
            Ed25519PrivateKeyParameters(seed(store, SIGNING_SEED), 0),
            X25519PrivateKeyParameters(seed(store, ENCRYPTION_SEED), 0),
        )

        private fun seed(store: KeyStore, key: String): ByteArray =
            store.read(key) ?: randomBytes(32).also { store.write(key, it) }
    }
}
