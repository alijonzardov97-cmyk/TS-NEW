package com.ts.messenger.push

import com.ts.messenger.net.AppJson
import com.ts.messenger.net.PushPayload
import com.ts.messenger.security.SecureStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * Receives UnifiedPush events. Messages arrive already decrypted by the connector library
 * (Web Push, RFC 8291) and only contain metadata; the actual message text is always fetched
 * and decrypted inside the app.
 */
class TsPushService : PushService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        // Our server needs the Web Push keys; an endpoint without them is of no use.
        val keys = endpoint.pubKeySet ?: return
        val registry = PushRegistry(SecureStore(applicationContext))
        registry.save(PushRegistry.Endpoint(endpoint.url, keys.pubKey, keys.auth))
        scope.launch {
            // Best effort; if there is no session yet, the app syncs on the next sign-in or start.
            runCatching {
                ServerClient.fromStore(applicationContext)?.let { registry.sync(it) }
            }
        }
    }

    override fun onMessage(message: PushMessage, instance: String) {
        val payload = if (message.decrypted) {
            runCatching {
                AppJson.decodeFromString<PushPayload>(String(message.content.copyOf(minOf(message.content.size, 4096)), Charsets.UTF_8))
            }.getOrNull()
        } else null
        Notifications.showNewMessage(applicationContext, payload)
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        PushRegistry(SecureStore(applicationContext)).clear()
    }

    override fun onUnregistered(instance: String) {
        PushRegistry(SecureStore(applicationContext)).clear()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
