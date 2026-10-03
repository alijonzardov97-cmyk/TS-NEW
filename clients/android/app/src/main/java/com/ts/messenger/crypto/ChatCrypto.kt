package com.ts.messenger.crypto

import com.ts.messenger.net.AppJson
import com.ts.messenger.net.KeyBundle
import com.ts.messenger.net.WireHeader
import com.ts.messenger.net.WireMessage
import com.ts.messenger.net.WireX3dh
import com.ts.messenger.net.toU8List
import com.ts.messenger.security.SecureStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uniffi.ts_crypto_ffi.ratchetDecrypt
import uniffi.ts_crypto_ffi.ratchetEncrypt
import uniffi.ts_crypto_ffi.x3dhInitiate
import uniffi.ts_crypto_ffi.x3dhRespond

/** The peer's identity key differs from the one we saved: possible interception or a new device. */
class PeerIdentityChangedException(val peerId: String, val newKey: ByteArray) : Exception("peer identity changed")

class EncryptedPayload(val ciphertext: List<Int>, val nonce: List<Int>)

fun List<Int>.toBytes(): ByteArray = ByteArray(size) { this[it].toByte() }

/**
 * End-to-end encryption for 1:1 conversations: X3DH to start a session, Double Ratchet after.
 * The wire format is the same as the web client's, so both can talk to each other.
 *
 * Session state holds secret keys and is stored only through [SecureStore] (Keystore-encrypted).
 * A session is saved only after an operation succeeded, so a failed or forged message never
 * advances or resets it. All operations are serialised by [lock] because ratchet order matters.
 */
class ChatCrypto(private val store: SecureStore, private val vault: KeyVault) {
    private val lock = Mutex()

    suspend fun <T> exclusive(block: suspend () -> T): T = lock.withLock { block() }

    /** Encrypts [text] for [peerId]. Call inside [exclusive]; [fetchBundle] is used for a new session. */
    suspend fun encrypt(peerId: String, text: String, fetchBundle: suspend () -> KeyBundle): EncryptedPayload {
        val signing = vault.signingKey() ?: error("no identity key")
        val verifying = vault.verifyingKey() ?: error("no identity key")
        try {
            var sessionJson = store.getString(sessionKey(peerId))
            var x3dh: WireX3dh? = null
            if (sessionJson == null) {
                val bundle = fetchBundle()
                val theirIdentity = bundle.identityKey.toBytes()
                checkPeerIdentity(peerId, theirIdentity)
                val init = x3dhInitiate(
                    signing,
                    theirIdentity,
                    bundle.signedPrekey.publicKey.toBytes(),
                    bundle.signedPrekey.signature.toBytes(),
                    bundle.oneTimePrekey?.publicKey?.toBytes(),
                )
                sessionJson = init.sessionJson
                store.put(peerKey(peerId), theirIdentity)
                x3dh = WireX3dh(
                    identityKey = verifying.toU8List(),
                    ephemeralKey = init.ephemeralPublicKey.toU8List(),
                    signedPrekeyId = bundle.signedPrekey.keyId,
                    oneTimePrekeyId = bundle.oneTimePrekey?.keyId,
                )
            }
            val enc = ratchetEncrypt(sessionJson, text.toByteArray(Charsets.UTF_8))
            store.putString(sessionKey(peerId), enc.sessionJson)
            val wire = WireMessage(
                x3dh = x3dh,
                header = WireHeader(
                    ratchetKey = enc.ratchetKey.toU8List(),
                    previousChainLength = enc.previousChainLength.toLong(),
                    messageNumber = enc.messageNumber.toLong(),
                ),
                ciphertext = enc.ciphertext.toU8List(),
                nonce = enc.nonce.toU8List(),
            )
            val bytes = AppJson.encodeToString(WireMessage.serializer(), wire).toByteArray(Charsets.UTF_8)
            return EncryptedPayload(bytes.toU8List(), enc.nonce.toU8List())
        } finally {
            signing.fill(0)
        }
    }

    /** Decrypts a message from [peerId]. Throws on any failure and leaves saved state untouched. */
    fun decrypt(peerId: String, ciphertext: ByteArray): String {
        val wire = AppJson.decodeFromString(WireMessage.serializer(), String(ciphertext, Charsets.UTF_8))
        require(wire.v == 1) { "unsupported wire version" }
        var sessionJson = store.getString(sessionKey(peerId))
        var newPeerIdentity: ByteArray? = null
        var spentOtpId: Int? = null

        val x3dh = wire.x3dh
        val ephemeral = x3dh?.ephemeralKey?.toBytes()
        // A repeated X3DH header (same ephemeral key) is a replay: never let it reset a session.
        val isNewHandshake = x3dh != null && (sessionJson == null || !ephemeral.contentEquals(store.get(ephKey(peerId))))
        if (isNewHandshake && x3dh != null && ephemeral != null) {
            val theirIdentity = x3dh.identityKey.toBytes()
            checkPeerIdentity(peerId, theirIdentity)
            val signing = vault.signingKey() ?: error("no identity key")
            val spk = vault.signedPrekeyPrivate(x3dh.signedPrekeyId) ?: error("unknown signed prekey")
            // Peek only: the one-time prekey is destroyed once decryption has succeeded.
            val otp = x3dh.oneTimePrekeyId?.let { vault.peekOneTimePrekey(it) }
            try {
                sessionJson = x3dhRespond(signing, spk, otp, theirIdentity, ephemeral)
                newPeerIdentity = theirIdentity
                spentOtpId = x3dh.oneTimePrekeyId
            } finally {
                signing.fill(0)
                spk.fill(0)
                otp?.fill(0)
            }
        }
        val session = sessionJson ?: error("no session")
        val dec = ratchetDecrypt(
            session,
            wire.header.ratchetKey.toBytes(),
            wire.header.previousChainLength.toUInt(),
            wire.header.messageNumber.toUInt(),
            wire.ciphertext.toBytes(),
            wire.nonce.toBytes(),
        )
        // Success: commit everything.
        store.putString(sessionKey(peerId), dec.sessionJson)
        newPeerIdentity?.let { store.put(peerKey(peerId), it) }
        if (isNewHandshake && ephemeral != null) store.put(ephKey(peerId), ephemeral)
        spentOtpId?.let { vault.takeOneTimePrekey(it)?.fill(0) }
        return String(dec.plaintext, Charsets.UTF_8)
    }

    /** The user accepted a changed identity: remember the new key and start a fresh session. */
    fun acceptPeerIdentity(peerId: String, newKey: ByteArray) {
        store.put(peerKey(peerId), newKey)
        store.remove(sessionKey(peerId))
        store.remove(ephKey(peerId))
    }

    /** Safety number inputs: our and the peer's identity public keys, if both are known. */
    fun identityKeys(peerId: String): Pair<ByteArray, ByteArray>? {
        val mine = vault.verifyingKey() ?: return null
        val theirs = store.get(peerKey(peerId)) ?: return null
        return mine to theirs
    }

    private fun checkPeerIdentity(peerId: String, key: ByteArray) {
        val saved = store.get(peerKey(peerId)) ?: return
        if (!saved.contentEquals(key)) throw PeerIdentityChangedException(peerId, key)
    }

    private fun sessionKey(peerId: String) = "session.${id(peerId)}"
    private fun peerKey(peerId: String) = "peer.${id(peerId)}"
    private fun ephKey(peerId: String) = "eph.${id(peerId)}"
    private fun id(peerId: String): String {
        require(peerId.matches(Regex("[0-9a-fA-F-]{36}"))) { "bad peer id" }
        return peerId.lowercase()
    }
}
