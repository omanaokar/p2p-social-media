package com.proximity.app.identity

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.proximity.core.identity.KeyStore
import java.security.KeyStore as JavaKeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Android implementation of [KeyStore] backed by the hardware-backed Android KeyStore
 * using AES-256 in GCM mode.
 *
 * Encrypted key seeds and values are stored in private SharedPreferences.
 * A graceful software fallback is provided for non-Android environments (such as JVM tests).
 */
class AndroidKeyStore(
    context: Context,
    prefName: String = "proximity_identity_keystore",
    private val keyAlias: String = "proximity_identity_master_key",
) : KeyStore {

    private val prefs = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)

    private val masterKey: SecretKey by lazy {
        try {
            getOrCreateKeyStoreSecretKey()
        } catch (_: Exception) {
            getOrCreateFallbackSecretKey()
        }
    }

    private fun getOrCreateKeyStoreSecretKey(): SecretKey {
        val ks = JavaKeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(keyAlias)) {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
            )
            val spec = KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGenerator.init(spec)
            return keyGenerator.generateKey()
        }
        val entry = ks.getEntry(keyAlias, null) as? JavaKeyStore.SecretKeyEntry
            ?: error("KeyStore entry for $keyAlias is not a SecretKeyEntry")
        return entry.secretKey
    }

    private fun getOrCreateFallbackSecretKey(): SecretKey {
        val fallbackAlias = "fallback_master_key"
        val existing = prefs.getString(fallbackAlias, null)
        if (existing != null) {
            val raw = Base64.decode(existing, Base64.NO_WRAP)
            return SecretKeySpec(raw, "AES")
        }
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        val generated = keyGen.generateKey()
        prefs.edit()
            .putString(fallbackAlias, Base64.encodeToString(generated.encoded, Base64.NO_WRAP))
            .apply()
        return generated
    }

    override fun read(key: String): ByteArray? {
        val base64 = prefs.getString(key, null) ?: return null
        return try {
            val combined = Base64.decode(base64, Base64.NO_WRAP)
            if (combined.size < GCM_IV_LENGTH_BYTES + GCM_TAG_LENGTH_BYTES) return null

            val iv = combined.copyOfRange(0, GCM_IV_LENGTH_BYTES)
            val cipherText = combined.copyOfRange(GCM_IV_LENGTH_BYTES, combined.size)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, masterKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            cipher.doFinal(cipherText)
        } catch (_: Exception) {
            null
        }
    }

    override fun write(key: String, value: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey)
        val iv = cipher.iv ?: error("Cipher IV is null")
        val cipherText = cipher.doFinal(value)

        val combined = ByteArray(iv.size + cipherText.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(cipherText, 0, combined, iv.size, cipherText.size)

        val encoded = Base64.encodeToString(combined, Base64.NO_WRAP)
        prefs.edit().putString(key, encoded).apply()
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH_BYTES = 12
        private const val GCM_TAG_LENGTH_BYTES = 16
        private const val GCM_TAG_LENGTH_BITS = 128
    }
}
