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

    fun hasKeys(): Boolean = store.get(K_IDENTITY_SIGNING)?.also { it.fill(0) } != null

    fun wipe() {
        listOf(
            K_IDENTITY_SIGNING, K_IDENTITY_VERIFYING, K_SIGNED_PREKEY_PRIVATE,
            K_SIGNED_PREKEY_ID, K_ONE_TIME_PREKEYS, K_KEY_VERSION,
        ).forEach(store::remove)
    }

    private companion object {
        // Matches the web client: signed prekey id 1, ids 1..100 for one-time prekeys, key version 2.
        const val SIGNED_PREKEY_ID = 1
        const val INITIAL_ONE_TIME_PREKEYS = 100
        const val KEY_VERSION = 2
        const val K_IDENTITY_SIGNING = "e2e.identity.signing"
        const val K_IDENTITY_VERIFYING = "e2e.identity.verifying"
        const val K_SIGNED_PREKEY_PRIVATE = "e2e.spk.private"
        const val K_SIGNED_PREKEY_ID = "e2e.spk.id"
        const val K_ONE_TIME_PREKEYS = "e2e.otp.packed"
        const val K_KEY_VERSION = "e2e.version"
    }
}
