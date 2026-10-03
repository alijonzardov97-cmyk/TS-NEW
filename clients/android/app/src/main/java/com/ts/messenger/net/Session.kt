package com.ts.messenger.net

import android.util.Base64
import com.ts.messenger.security.SecureStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Holds the access/refresh tokens (encrypted at rest) and keeps the access token fresh.
 * Refresh tokens rotate: each refresh returns a new pair, and both are stored together.
 */
class Session(
    private val store: SecureStore,
    private val refreshCall: suspend (String) -> TokenResponse,
) {
    // Shared by every instance (UI and push service) so a rotating refresh token is never used twice.
    private val mutex = LOCK

    /** A valid access token, refreshing first if it expires within a minute. Null if signed out. */
    suspend fun accessToken(): String? = mutex.withLock {
        val token = store.getString(K_ACCESS) ?: return@withLock refreshLocked()
        if (expiresWithin(token, 60)) refreshLocked() ?: token else token
    }

    /** Forces a refresh (used after a 401). Null if the refresh token is no longer valid. */
    suspend fun forceRefresh(): String? = mutex.withLock { refreshLocked() }

    fun save(access: String, refresh: String) {
        store.putString(K_ACCESS, access)
        store.putString(K_REFRESH, refresh)
    }

    fun hasRefreshToken(): Boolean = store.get(K_REFRESH)?.also { it.fill(0) } != null

    private suspend fun refreshLocked(): String? {
        val refresh = store.getString(K_REFRESH) ?: return null
        return try {
            val res = refreshCall(refresh)
            save(res.accessToken, res.refreshToken)
            res.accessToken
        } catch (e: ApiException) {
            if (e.status == 401 || e.status == 403) {
                // The server rejected the refresh token: the session is over.
                store.remove(K_ACCESS)
                store.remove(K_REFRESH)
                null
            } else {
                throw e
            }
        }
    }

    private fun expiresWithin(jwt: String, seconds: Long): Boolean {
        val exp = runCatching {
            val payload = jwt.split('.').getOrNull(1) ?: return true
            val json = String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
            Json.parseToJsonElement(json).jsonObject.expOrNull()
        }.getOrNull() ?: return true
        return exp - System.currentTimeMillis() / 1000 < seconds
    }

    private fun JsonObject.expOrNull(): Long? = this["exp"]?.jsonPrimitive?.longOrNull

    companion object {
        private val LOCK = Mutex()
        const val K_ACCESS = "auth.access"
        const val K_REFRESH = "auth.refresh"
    }
}
