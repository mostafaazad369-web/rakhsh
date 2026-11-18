package com.rakhsh.android.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts small credentials at rest with a non-exportable Android Keystore key.
 *
 * Store only credentials here; do not store generated TOML/config files containing
 * plaintext secrets. Callers should keep decrypted values in memory only as long
 * as needed and must never log them.
 */
class SecretStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply {
        load(null)
    }

    @Synchronized
    fun save(name: String, secret: String) {
        require(name in ALLOWED_NAMES) { "Unsupported secret name" }
        require(secret.isNotEmpty()) { "Secret must not be empty" }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))

        val ciphertext = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        require(iv.size in MIN_IV_SIZE..MAX_IV_SIZE) { "Unexpected GCM IV size" }

        val payload = ByteBuffer.allocate(Int.SIZE_BYTES + iv.size + ciphertext.size)
            .putInt(iv.size)
            .put(iv)
            .put(ciphertext)
            .array()

        check(prefs.edit()
            .putString(name, Base64.encodeToString(payload, Base64.NO_WRAP))
            .commit()) { "Could not persist encrypted secret" }
    }

    @Synchronized
    fun read(name: String): String? {
        require(name in ALLOWED_NAMES) { "Unsupported secret name" }
        val encoded = prefs.getString(name, null) ?: return null

        // Deliberately do not generate a replacement key here. If the original
        // Keystore key was invalidated/lost, decryption must fail visibly and
        // the user must import the credentials again.
        val key = keyStore.getKey(KEY_ALIAS, null)
            ?: throw IllegalStateException("Credential key is missing; re-import credentials")

        val payload = try {
            Base64.decode(encoded, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("Stored credential is corrupt", e)
        }

        if (payload.size < Int.SIZE_BYTES + MIN_IV_SIZE + TAG_SIZE_BYTES) {
            throw IllegalStateException("Stored credential is truncated")
        }

        val buffer = ByteBuffer.wrap(payload)
        val ivSize = buffer.int
        if (ivSize !in MIN_IV_SIZE..MAX_IV_SIZE ||
            buffer.remaining() < ivSize + TAG_SIZE_BYTES
        ) {
            throw IllegalStateException("Stored credential has an invalid format")
        }

        val iv = ByteArray(ivSize).also(buffer::get)
        val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))

        return try {
            cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
        } catch (e: Exception) {
            throw IllegalStateException("Could not decrypt credential; re-import it", e)
        }
    }

    @Synchronized
    fun delete(name: String) {
        require(name in ALLOWED_NAMES) { "Unsupported secret name" }
        check(prefs.edit().remove(name).commit()) {
            "Could not delete encrypted secret"
        }
    }

    @Synchronized
    fun clear() {
        check(prefs.edit().clear().commit()) {
            "Could not clear encrypted secrets"
        }
    }

    private fun getOrCreateKey(): java.security.Key {
        keyStore.getKey(KEY_ALIAS, null)?.let { return it }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEY_STORE
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS_NAME = "rakhsh_secure_secrets"
        const val KEY_ALIAS = "rakhsh_master_aes_key"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val TAG_SIZE_BYTES = TAG_BITS / 8
        const val MIN_IV_SIZE = 12
        const val MAX_IV_SIZE = 16

        val ALLOWED_NAMES = setOf("config", "token", "static_secret")
    }
}
