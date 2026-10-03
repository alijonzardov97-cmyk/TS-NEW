package com.ts.messenger.push

import com.ts.messenger.net.AppJson
import com.ts.messenger.net.TsApi
import com.ts.messenger.security.SecureStore
import kotlinx.serialization.Serializable

/**
 * Remembers the UnifiedPush endpoint (a URL plus Web Push keys) in encrypted storage and makes
 * sure the server knows it. The endpoint can arrive before the user is signed in, so syncing is
 * repeated whenever a session starts.
 */
class PushRegistry(private val store: SecureStore) {
    @Serializable
    data class Endpoint(val url: String, val p256dh: String, val auth: String)

    fun save(endpoint: Endpoint) {
        store.putString(K_ENDPOINT, AppJson.encodeToString(Endpoint.serializer(), endpoint))
        store.remove(K_SYNCED)
    }

    fun load(): Endpoint? = store.getString(K_ENDPOINT)
        ?.let { runCatching { AppJson.decodeFromString(Endpoint.serializer(), it) }.getOrNull() }

    fun clear() {
        store.remove(K_ENDPOINT)
        store.remove(K_SYNCED)
    }

    /** Tells the server about the current endpoint if it does not know it yet. */
    suspend fun sync(api: TsApi) {
        val e = load() ?: return
        if (store.getString(K_SYNCED) == e.url) return
        api.subscribePush(e.url, e.p256dh, e.auth)
        store.putString(K_SYNCED, e.url)
    }

    suspend fun unsubscribe(api: TsApi) {
        val e = load() ?: return
        api.unsubscribePush(e.url)
    }

    private companion object {
        const val K_ENDPOINT = "push.endpoint"
        const val K_SYNCED = "push.synced"
    }
}
