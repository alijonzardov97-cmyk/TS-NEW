package com.ts.messenger.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM encryption with a non-exportable key that lives in the Android Keystore
 * (StrongBox secure element when the device has one, otherwise the TEE).
 *
 * - The key never leaves secure hardware, so a copy of the app's files is useless on its own.
 * - `setUnlockedDeviceRequired` makes the key unusable while the phone is locked.
 * - Each blob is bound to a caller-supplied label (AAD) so blobs cannot be swapped around.
 */
object KeystoreVault {
    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "ts_vault_key_v1"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    @Synchronized
    private fun key(): SecretKey {
        val ks = keyStore()
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return try {
            generate(strongBox = true)
        } catch (_: Exception) {
            // Device without StrongBox: fall back to the TEE-backed Keystore.
            generate(strongBox = false)
        }
    }

    private fun generate(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setUnlockedDeviceRequired(true)
            .setIsStrongBoxBacked(strongBox)
            .build()
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        gen.init(spec)
        return gen.generateKey()
    }

    /** Returns `iv || ciphertext+tag`. */
    fun encrypt(plain: ByteArray, label: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // Let the Keystore pick the IV (required when randomized encryption is enforced).
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(label.toByteArray(Charsets.UTF_8))
        val ct = cipher.doFinal(plain)
        return cipher.iv + ct
    }

    fun decrypt(blob: ByteArray, label: String): ByteArray {
        require(blob.size > IV_BYTES + GCM_TAG_BITS / 8) { "blob too short" }
        val iv = blob.copyOfRange(0, IV_BYTES)
        val ct = blob.copyOfRange(IV_BYTES, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(label.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(ct)
    }

    /** Deletes the master key. Every stored blob becomes permanently unreadable. */
    fun destroyKey() {
        runCatching { keyStore().deleteEntry(ALIAS) }
    }
}
