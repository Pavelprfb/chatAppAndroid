package com.chatapp.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

class RefreshTokenStore(context: Context) {
    private val preferences = context.getSharedPreferences("secure_session", Context.MODE_PRIVATE)
    private val keyAlias = "chatapp.refresh-token.v1"

    fun read(): String? {
        val encoded = preferences.getString(TOKEN_KEY, null) ?: return null
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        val ivLength = payload.firstOrNull()?.toInt()
            ?: throw IllegalArgumentException("Stored encrypted value is empty")
        require(ivLength in 12..16 && payload.size > ivLength) { "Stored refresh token is malformed" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, payload.copyOfRange(1, 1 + ivLength)))
        return String(cipher.doFinal(payload.copyOfRange(1 + ivLength, payload.size)), Charsets.UTF_8)
    }

    fun write(token: String) {
        writeValue(TOKEN_KEY, token)
    }

    fun readValue(key: String): String? {
        val encoded = preferences.getString(key, null) ?: return null
        return decryptValue(encoded)
    }

    fun encryptValue(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val payload = byteArrayOf(iv.size.toByte()) + iv + encrypted
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    fun decryptValue(encoded: String): String {
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        val ivLength = payload.firstOrNull()?.toInt()
            ?: throw IllegalArgumentException("Stored encrypted value is empty")
        require(ivLength in 12..16 && payload.size > ivLength) { "Stored encrypted value is malformed" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, payload.copyOfRange(1, 1 + ivLength)))
        return String(cipher.doFinal(payload.copyOfRange(1 + ivLength, payload.size)), Charsets.UTF_8)
    }

    fun writeValue(key: String, value: String) {
        check(preferences.edit().putString(key, encryptValue(value)).commit()) {
            "Unable to persist encrypted local data"
        }
    }

    fun clear() {
        clearValue(TOKEN_KEY)
    }

    fun clearValue(key: String) {
        check(preferences.edit().remove(key).commit()) {
            "Unable to clear the local refresh token"
        }
    }

    fun clearValuesWithPrefix(prefix: String) {
        val editor = preferences.edit()
        preferences.all.keys.filter { it.startsWith(prefix) }.forEach(editor::remove)
        check(editor.commit()) { "Unable to clear encrypted local cache" }
    }

    private fun key(): java.security.Key {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        keyStore.getKey(keyAlias, null)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val TOKEN_KEY = "refresh_token"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
