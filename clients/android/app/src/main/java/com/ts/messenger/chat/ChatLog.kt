package com.ts.messenger.chat

import com.ts.messenger.net.AppJson
import com.ts.messenger.net.ChatMessage
import com.ts.messenger.security.SecureStore
import kotlinx.serialization.builtins.ListSerializer

/**
 * Local, encrypted log of decrypted messages, one blob per conversation.
 *
 * A Double Ratchet message key can be used only once, so a message can never be decrypted a
 * second time: the plaintext has to be kept. It is stored encrypted under the Android Keystore,
 * in the no-backup directory, and wiped on sign-out together with everything else.
 */
class ChatLog(private val store: SecureStore) {
    private val serializer = ListSerializer(ChatMessage.serializer())

    fun load(channelId: String): List<ChatMessage> {
        val raw = store.getString(key(channelId)) ?: return emptyList()
        return runCatching { AppJson.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }

    /** Adds or replaces messages (by id), keeps them ordered by time and caps the log. */
    @Synchronized
    fun upsert(channelId: String, messages: List<ChatMessage>): List<ChatMessage> {
        val byId = LinkedHashMap<String, ChatMessage>()
        load(channelId).forEach { byId[it.id] = it }
        messages.forEach { new ->
            val old = byId[new.id]
            // Never replace a successfully decrypted message with a failure placeholder.
            if (old == null || !old.ok || new.ok) byId[new.id] = new
        }
        val merged = byId.values.sortedBy { it.createdAt }.takeLast(MAX_MESSAGES)
        store.putString(key(channelId), AppJson.encodeToString(serializer, merged))
        return merged
    }

    /** Replaces the whole log of a conversation (used when old messages are purged). */
    @Synchronized
    fun replace(channelId: String, messages: List<ChatMessage>) {
        store.putString(key(channelId), AppJson.encodeToString(serializer, messages))
    }

    private fun key(channelId: String): String {
        require(channelId.matches(Regex("[0-9a-fA-F-]{36}"))) { "bad channel id" }
        return "chat.${channelId.lowercase()}"
    }

    private companion object {
        const val MAX_MESSAGES = 1000
    }
}
