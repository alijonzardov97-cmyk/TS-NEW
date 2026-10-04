package com.ts.messenger.chat

import com.ts.messenger.crypto.ChatCrypto
import com.ts.messenger.crypto.PeerIdentityChangedException
import com.ts.messenger.crypto.toBytes
import com.ts.messenger.files.FileCrypto
import com.ts.messenger.files.FileService
import com.ts.messenger.net.AppJson
import com.ts.messenger.net.ChatMessage
import com.ts.messenger.net.FileRef
import com.ts.messenger.net.WireFile
import com.ts.messenger.net.WireFileEnc
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.util.Base64
import com.ts.messenger.net.ChatSocket
import com.ts.messenger.net.DmChannel
import com.ts.messenger.net.MessageDto
import com.ts.messenger.net.TsApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap

class NotConnectedException : Exception("not connected")

/**
 * Ties the pieces together for 1:1 conversations: encrypts and sends, receives and decrypts,
 * and keeps the local encrypted log. Everything that touches ratchet state runs inside
 * [ChatCrypto.exclusive], so sessions are never used concurrently.
 */
class ChatRepository(
    private val api: TsApi,
    private val crypto: ChatCrypto,
    private val log: ChatLog,
    private val socket: ChatSocket,
    private val myId: String,
    private val files: FileService,
) {
    /** Messages older than this many days are removed from this phone; 0 keeps everything. */
    @Volatile var ttlDays: Int = 0

    /** Messages at or before this moment were deleted by the user and are never shown again. */
    @Volatile var clearedBefore: java.time.Instant? = null

    private fun expired(createdAt: String): Boolean {
        val t = runCatching { java.time.OffsetDateTime.parse(createdAt).toInstant() }.getOrNull() ?: return false
        clearedBefore?.let { if (!t.isAfter(it)) return true }
        if (ttlDays <= 0) return false
        return t.isBefore(java.time.Instant.now().minus(java.time.Duration.ofDays(ttlDays.toLong())))
    }

    /** Drops expired messages from the local log. Returns true if something was removed. */
    fun purgeExpired(channelId: String): Boolean {
        if (ttlDays <= 0 && clearedBefore == null) return false
        val all = log.load(channelId)
        val keep = all.filter { !expired(it.createdAt) }
        if (keep.size == all.size) return false
        log.replace(channelId, keep)
        _changed.tryEmit(channelId)
        return true
    }

    private class Pending(val channelId: String, val text: String, val file: FileRef? = null)

    private val pending = ArrayDeque<Pending>()
    private val peers = ConcurrentHashMap<String, String>() // channel id -> peer user id

    private val _changed = MutableSharedFlow<String>(extraBufferCapacity = 64)
    /** Emits the id of a conversation whose local log changed. */
    val changed: SharedFlow<String> = _changed.asSharedFlow()

    private val _identityAlerts = MutableSharedFlow<PeerIdentityChangedException>(extraBufferCapacity = 8)
    val identityAlerts: SharedFlow<PeerIdentityChangedException> = _identityAlerts.asSharedFlow()

    fun registerDms(dms: List<DmChannel>) {
        dms.forEach { peers[it.channel.id] = it.otherUser.id }
    }

    fun cached(channelId: String): List<ChatMessage> = log.load(channelId)

    /** Encrypts [text] and hands it to the socket. Nothing is encrypted while offline. */
    suspend fun send(dm: DmChannel, text: String) {
        val channelId = dm.channel.id
        val peerId = dm.otherUser.id
        crypto.exclusive {
            if (!socket.isReady) throw NotConnectedException()
            val payload = crypto.encrypt(peerId, text) { api.keyBundle(peerId) }
            val entry = Pending(channelId, text)
            // Registered before sending: the server's confirmation can arrive immediately.
            synchronized(pending) { pending.addLast(entry) }
            if (!socket.sendMessage(channelId, payload.ciphertext, payload.nonce)) {
                synchronized(pending) { pending.remove(entry) }
                throw NotConnectedException()
            }
        }
    }

    /** Encrypts [p] with a fresh key, uploads the ciphertext and sends the key in an E2E message. */
    suspend fun sendFile(dm: DmChannel, p: FileService.Picked) {
        val channelId = dm.channel.id
        val peerId = dm.otherUser.id
        if (!socket.isReady) throw NotConnectedException()
        val ref = files.upload(channelId, p)
        val text = AppJson.encodeToString(
            WireFile(ref.id, ref.name, ref.size, WireFileEnc(1, ref.key, FileCrypto.CHUNK, ref.mime)),
        )
        try {
            crypto.exclusive {
                if (!socket.isReady) throw NotConnectedException()
                val payload = crypto.encrypt(peerId, text) { api.keyBundle(peerId) }
                val entry = Pending(channelId, ref.name, ref)
                synchronized(pending) { pending.addLast(entry) }
                if (!socket.sendMessage(channelId, payload.ciphertext, payload.nonce, "file")) {
                    synchronized(pending) { pending.remove(entry) }
                    throw NotConnectedException()
                }
            }
        } catch (e: Throwable) {
            files.discard(ref.id)
            // Do not leave an orphaned ciphertext (and its quota) on the server.
            runCatching { api.deleteFile(ref.id) }
            throw e
        }
    }

    /** The server accepted our oldest unconfirmed message in this conversation. */
    fun onSent(id: String, channelId: String, createdAt: String) {
        val p = synchronized(pending) {
            val i = pending.indexOfFirst { it.channelId == channelId }
            if (i < 0) null else pending.removeAt(i)
        } ?: return
        log.upsert(channelId, listOf(ChatMessage(id, myId, p.text, createdAt, file = p.file)))
        _changed.tryEmit(channelId)
    }

    suspend fun onIncoming(m: MessageDto) {
        val sender = m.senderId ?: return
        if (sender == myId || (m.messageType != "text" && m.messageType != "file")) return // our own echo is logged by onSent
        val peerId = peers[m.channelId] ?: run {
            // A conversation we have not seen yet (the other side started it).
            registerDms(api.listDms())
            peers[m.channelId]
        } ?: return
        crypto.exclusive {
            val msg = decryptOne(peerId, m)
            log.upsert(m.channelId, listOf(msg))
        }
        _changed.tryEmit(m.channelId)
    }

    /** Fetches recent messages and decrypts the ones the log does not have yet. */
    suspend fun loadHistory(dm: DmChannel) {
        val channelId = dm.channel.id
        val peerId = dm.otherUser.id
        registerDms(listOf(dm))
        val dtos = api.messages(channelId, 50).sortedBy { it.createdAt }
        val known = log.load(channelId).filter { it.ok }.map { it.id }.toHashSet()
        val fresh = ArrayList<ChatMessage>()
        crypto.exclusive {
            for (m in dtos) {
                val sender = m.senderId ?: continue
                if (m.id in known || (m.messageType != "text" && m.messageType != "file") || expired(m.createdAt)) continue
                fresh += if (sender == myId) {
                    // Our own ciphertext cannot be decrypted by us; only the plaintext kept at
                    // send time on this device is readable.
                    ChatMessage(m.id, sender, "", m.createdAt, ok = false)
                } else {
                    decryptOne(peerId, m)
                }
            }
        }
        if (fresh.isNotEmpty()) {
            log.upsert(channelId, fresh)
            _changed.tryEmit(channelId)
        }
    }

    /** The user trusts the peer's new identity key: reset the session and retry what failed. */
    suspend fun acceptIdentity(alert: PeerIdentityChangedException) {
        crypto.exclusive { crypto.acceptPeerIdentity(alert.peerId, alert.newKey) }
    }

    fun safetyKeys(peerId: String) = crypto.identityKeys(peerId)

    private fun decryptOne(peerId: String, m: MessageDto): ChatMessage {
        val sender = m.senderId ?: peerId
        return try {
            val text = crypto.decrypt(peerId, m.ciphertext.toBytes())
            if (m.messageType == "file") parseFile(m, sender, text) else ChatMessage(m.id, sender, text, m.createdAt)
        } catch (e: PeerIdentityChangedException) {
            _identityAlerts.tryEmit(e)
            ChatMessage(m.id, sender, "", m.createdAt, ok = false)
        } catch (_: Exception) {
            ChatMessage(m.id, sender, "", m.createdAt, ok = false)
        }
    }

    /** The peer controls this JSON: every field is validated before it is trusted. */
    private fun parseFile(m: MessageDto, sender: String, text: String): ChatMessage {
        val w = AppJson.decodeFromString<WireFile>(text)
        val name = FileService.sanitizeName(w.filename)
        val enc = w.enc
        // No encryption info: a file from the web client, which uploads in plaintext. Not opened here.
        if (enc == null) return ChatMessage(m.id, sender, name, m.createdAt, file = FileRef("", name, 0, "", ""))
        require(FileService.ID.matches(w.fileId) && enc.v == 1 && enc.chunk == FileCrypto.CHUNK)
        require(w.size in 0..FileService.MAX_FILE_BYTES)
        require(Base64.getUrlDecoder().decode(enc.key).size == 32)
        val mime = enc.mime.take(100).lowercase().takeIf { Regex("[a-z0-9.+-]+/[a-z0-9.+-]+").matches(it) }
            ?: "application/octet-stream"
        return ChatMessage(m.id, sender, name, m.createdAt, file = FileRef(w.fileId, name, w.size, mime, enc.key))
    }
}
