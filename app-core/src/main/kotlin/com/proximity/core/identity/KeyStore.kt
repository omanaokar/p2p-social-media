package com.proximity.core.identity

import java.util.concurrent.ConcurrentHashMap

/**
 * Where private key material lives. The Android app should back this with
 * EncryptedSharedPreferences or a Keystore-wrapped file; tests use
 * [InMemoryKeyStore].
 */
interface KeyStore {
    fun read(key: String): ByteArray?
    fun write(key: String, value: ByteArray)
}

class InMemoryKeyStore : KeyStore {
    private val values = ConcurrentHashMap<String, ByteArray>()

    override fun read(key: String): ByteArray? = values[key]?.copyOf()

    override fun write(key: String, value: ByteArray) {
        values[key] = value.copyOf()
    }
}
