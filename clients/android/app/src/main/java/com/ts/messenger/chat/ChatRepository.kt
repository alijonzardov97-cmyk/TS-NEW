package com.ts.messenger.chat

import com.ts.messenger.crypto.ChatCrypto
import com.ts.messenger.crypto.PeerIdentityChangedException
import com.ts.messenger.crypto.toBytes
import com.ts.messenger.net.ChatMessage
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
) {
    private class Pending(val channelId: String, val text: String)

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
            if (!socket.sendMessage(channelId, payload.ciphertext, payload.nonce)) throw NotConnectedException()
            synchronized(pending) { pending.addLast(Pending(channelId, text)) }
        }
    }

    /** The server accepted our oldest unconfirmed message in this conversation. */
    fun onSent(id: String, channelId: String, createdAt: String) {
        val p = synchronized(pending) {
            val i = pending.indexOfFirst { it.channelId == channelId }
            if (i < 0) null else pending.removeAt(i)
        } ?: return
        log.upsert(channelId, listOf(ChatMessage(id, myId, p.text, createdAt)))
        _changed.tryEmit(channelId)
    }

    suspend fun onIncoming(m: MessageDto) {
        val sender = m.senderId ?: return
        if (sender == myId || m.messageType != "text") return // our own echo is logged by onSent
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
                if (m.id in known || m.messageType != "text") continue
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
            ChatMessage(m.id, sender, crypto.decrypt(peerId, m.ciphertext.toBytes()), m.createdAt)
        } catch (e: PeerIdentityChangedException) {
            _identityAlerts.tryEmit(e)
            ChatMessage(m.id, sender, "", m.createdAt, ok = false)
        } catch (_: Exception) {
            ChatMessage(m.id, sender, "", m.createdAt, ok = false)
        }
    }
}
