package com.ts.messenger.crypto

import com.ts.messenger.net.OneTimePrekeyUpload
import com.ts.messenger.net.SignedPrekeyUpload
import com.ts.messenger.net.toU8List
import com.ts.messenger.security.SecureStore
import uniffi.ts_crypto_ffi.generateIdentityKey
import uniffi.ts_crypto_ffi.generateOneTimePrekeys
import uniffi.ts_crypto_ffi.generateSignedPrekey

/** Public material to send to the server when registering. */
class RegistrationKeys(
    val identityKey: List<Int>,
    val signedPrekey: SignedPrekeyUpload,
    val oneTimePrekeys: List<OneTimePrekeyUpload>,
)

/**
 * Generates the end-to-end encryption keys with the shared Rust implementation and stores the
 * private halves encrypted under the Android Keystore. Private key bytes only exist in plain
 * form inside this function and are overwritten with zeros before it returns.
 */
class KeyVault(private val store: SecureStore) {

    fun generateRegistrationKeys(): RegistrationKeys {
        val identity = generateIdentityKey()
        val spk = generateSignedPrekey(identity.signingKey, SIGNED_PREKEY_ID)
        val otps = generateOneTimePrekeys(1, INITIAL_ONE_TIME_PREKEYS.toUInt())
        try {
            store.put(K_IDENTITY_SIGNING, identity.signingKey)
            store.put(K_IDENTITY_VERIFYING, identity.verifyingKey)
            store.put(K_SIGNED_PREKEY_PRIVATE, spk.privateKey)
            store.putString(K_SIGNED_PREKEY_ID, spk.keyId.toString())
            // One-time prekeys: id (4 bytes) + 32-byte private key, concatenated.
            val packed = ByteArray(otps.size * 36)
            otps.forEachIndexed { i, otp ->
                val off = i * 36
                packed[off] = (otp.keyId ushr 24).toByte()
                packed[off + 1] = (otp.keyId ushr 16).toByte()
                packed[off + 2] = (otp.keyId ushr 8).toByte()
                packed[off + 3] = otp.keyId.toByte()
                otp.privateKey.copyInto(packed, off + 4)
            }
            store.put(K_ONE_TIME_PREKEYS, packed)
            packed.fill(0)
            store.putString(K_KEY_VERSION, KEY_VERSION.toString())

            return RegistrationKeys(
                identityKey = identity.verifyingKey.toU8List(),
                signedPrekey = SignedPrekeyUpload(spk.keyId, spk.publicKey.toU8List(), spk.signature.toU8List()),
                oneTimePrekeys = otps.map { OneTimePrekeyUpload(it.keyId, it.publicKey.toU8List()) },
            )
        } finally {
            identity.signingKey.fill(0)
            spk.privateKey.fill(0)
            otps.forEach { it.privateKey.fill(0) }
        }
    }

    /** Identity signing key (private). The caller must zero the array after use. */
    fun signingKey(): ByteArray? = store.get(K_IDENTITY_SIGNING)

    fun verifyingKey(): ByteArray? = store.get(K_IDENTITY_VERIFYING)

    /** Private half of our signed prekey, only if [id] is the one we currently hold. */
    fun signedPrekeyPrivate(id: Int): ByteArray? =
        if (store.getString(K_SIGNED_PREKEY_ID)?.toIntOrNull() == id) store.get(K_SIGNED_PREKEY_PRIVATE) else null

    /** Reads a one-time prekey's private key without deleting it. */
    @Synchronized
    fun peekOneTimePrekey(id: Int): ByteArray? = scanOneTimePrekeys(id, remove = false)

    /** Returns the one-time prekey's private key and deletes it: each one is single use. */
    @Synchronized
    fun takeOneTimePrekey(id: Int): ByteArray? = scanOneTimePrekeys(id, remove = true)

    private fun scanOneTimePrekeys(id: Int, remove: Boolean): ByteArray? {
        val packed = store.get(K_ONE_TIME_PREKEYS) ?: return null
        try {
            var found: ByteArray? = null
            val keep = java.io.ByteArrayOutputStream()
            var off = 0
            while (off + 36 <= packed.size) {
                val keyId = ((packed[off].toInt() and 0xFF) shl 24) or ((packed[off + 1].toInt() and 0xFF) shl 16) or
                    ((packed[off + 2].toInt() and 0xFF) shl 8) or (packed[off + 3].toInt() and 0xFF)
                if (keyId == id && found == null) {
                    found = packed.copyOfRange(off + 4, off + 36)
                } else {
                    keep.write(packed, off, 36)
                }
                off += 36
            }
            if (remove && found != null) {
                val remaining = keep.toByteArray()
                store.put(K_ONE_TIME_PREKEYS, remaining)
                remaining.fill(0)
            }
            return found
        } finally {
            packed.fill(0)
        }
    }

    /**
     * Creates [count] more one-time prekeys, stores their private halves first and returns the
     * public halves for upload. Ids never repeat: the next free id is persisted.
     */
    @Synchronized
    fun generateMoreOneTimePrekeys(count: Int): List<OneTimePrekeyUpload> {
        val old = store.get(K_ONE_TIME_PREKEYS) ?: ByteArray(0)
        if (old.size / 36 + count > MAX_LOCAL_ONE_TIME_PREKEYS) { old.fill(0); return emptyList() }
        val start = store.getString(K_OTP_NEXT_ID)?.toIntOrNull() ?: (INITIAL_ONE_TIME_PREKEYS + 1)
        val otps = generateOneTimePrekeys(start, count.toUInt())
        val packed = ByteArray(old.size + otps.size * 36)
        try {
            old.copyInto(packed)
            otps.forEachIndexed { i, otp ->
                val off = old.size + i * 36
                packed[off] = (otp.keyId ushr 24).toByte()
                packed[off + 1] = (otp.keyId ushr 16).toByte()
                packed[off + 2] = (otp.keyId ushr 8).toByte()
                packed[off + 3] = otp.keyId.toByte()
                otp.privateKey.copyInto(packed, off + 4)
            }
            store.put(K_ONE_TIME_PREKEYS, packed)
            store.putString(K_OTP_NEXT_ID, (start + count).toString())
            return otps.map { OneTimePrekeyUpload(it.keyId, it.publicKey.toU8List()) }
        } finally {
            old.fill(0)
            packed.fill(0)
            otps.forEach { it.privateKey.fill(0) }
        }
    }

    fun hasKeys(): Boolean = store.get(K_IDENTITY_SIGNING)?.also { it.fill(0) } != null

    fun wipe() {
        listOf(
            K_IDENTITY_SIGNING, K_IDENTITY_VERIFYING, K_SIGNED_PREKEY_PRIVATE,
            K_SIGNED_PREKEY_ID, K_ONE_TIME_PREKEYS, K_KEY_VERSION, K_OTP_NEXT_ID,
        ).forEach(store::remove)
    }

    private companion object {
        // Matches the web client: signed prekey id 1, ids 1..100 for one-time prekeys, key version 2.
        const val SIGNED_PREKEY_ID = 1
        const val INITIAL_ONE_TIME_PREKEYS = 100
        const val KEY_VERSION = 2
        const val MAX_LOCAL_ONE_TIME_PREKEYS = 400
        const val K_OTP_NEXT_ID = "e2e.otp.next"
        const val K_IDENTITY_SIGNING = "e2e.identity.signing"
        const val K_IDENTITY_VERIFYING = "e2e.identity.verifying"
        const val K_SIGNED_PREKEY_PRIVATE = "e2e.spk.private"
        const val K_SIGNED_PREKEY_ID = "e2e.spk.id"
        const val K_ONE_TIME_PREKEYS = "e2e.otp.packed"
        const val K_KEY_VERSION = "e2e.version"
    }
}
