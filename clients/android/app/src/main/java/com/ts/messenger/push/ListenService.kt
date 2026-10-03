package com.ts.messenger.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ts.messenger.MainActivity
import com.ts.messenger.R
import com.ts.messenger.net.AppJson
import com.ts.messenger.net.ChatSocket
import com.ts.messenger.net.HttpClientFactory
import com.ts.messenger.net.PushPayload
import com.ts.messenger.net.Session
import com.ts.messenger.net.SocketEvent
import com.ts.messenger.net.TsApi
import com.ts.messenger.net.UserPublic
import com.ts.messenger.security.SecureStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Keeps one pinned, authenticated socket to the server open while the app is closed, so new
 * messages and calls raise a notification without any third-party push service. Notifications
 * carry metadata only (sender name); the text is fetched and decrypted when the app is opened.
 * It stays quiet while the app is on screen (the app has its own socket then).
 */
class ListenService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: ChatSocket? = null

    /** channel id -> display name of the other person. */
    @Volatile private var dms: Map<String, String> = emptyMap()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (socket == null) begin()
        return START_STICKY
    }

    private fun begin() {
        val store = SecureStore(applicationContext)
        val url = store.getString("server.url")?.toHttpUrlOrNull()
        val pins = store.getString("server.pins")
            ?.let { runCatching { AppJson.decodeFromString<List<String>>(it) }.getOrNull() }
            .orEmpty()
        val me = store.getString("auth.user")
            ?.let { runCatching { AppJson.decodeFromString<UserPublic>(it).id }.getOrNull() }
        if (url == null || pins.isEmpty() || me == null) {
            stopSelf()
            return
        }
        val client = HttpClientFactory.create(url.host, pins)
        val api = TsApi(url, client)
        api.session = Session(store) { api.refresh(it) }
        val sock = ChatSocket(
            client, url, scope,
            tokenProvider = { api.session?.accessToken() },
            channelIds = { dms.keys.toList() },
        )
        socket = sock
        scope.launch { sock.events.collect { handle(it, me) } }
        scope.launch {
            while (isActive) {
                refreshDms(api, sock)
                delay(120_000)
            }
        }
        sock.start()
    }

    private suspend fun refreshDms(api: TsApi, sock: ChatSocket) {
        try {
            val list = api.listDms()
            val known = dms.keys
            dms = list.associate { it.channel.id to it.otherUser.displayName }
            list.forEach { if (it.channel.id !in known) sock.subscribe(it.channel.id) }
        } catch (_: Exception) {
            // Offline or signed out; tried again on the next round.
        }
    }

    private fun handle(e: SocketEvent, me: String) {
        if (Notifications.appVisible) return
        when (e) {
            is SocketEvent.Incoming -> {
                val m = e.message
                val sender = m.senderId ?: return
                if (sender == me || (m.messageType != "text" && m.messageType != "file")) return
                if (!Notifications.claim(m.id)) return
                Notifications.showNewMessage(
                    applicationContext, PushPayload("new_message", dms[m.channelId].orEmpty(), m.channelId),
                )
            }
            is SocketEvent.Voice -> {
                val ch = e.data["channel_id"]?.jsonPrimitive?.contentOrNull ?: return
                val who = e.data["user_id"]?.jsonPrimitive?.contentOrNull ?: return
                if (who == me || ch !in dms) return
                when (e.type) {
                    "user_joined_voice" ->
                        if (Notifications.claim("call:$ch:$who:${System.currentTimeMillis() / 30_000}")) {
                            Notifications.showIncomingCall(applicationContext, dms[ch], ch)
                        }
                    "user_left_voice" -> Notifications.cancelCall(applicationContext)
                }
            }
            else -> {}
        }
    }

    private fun buildNotification(): android.app.Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.bg_channel), NotificationManager.IMPORTANCE_MIN),
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.bg_notification))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(open)
            .build()
    }

    override fun onDestroy() {
        socket?.stop()
        socket = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "listener"
        private const val NOTIFICATION_ID = 7002

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, ListenService::class.java)) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, ListenService::class.java)) }
        }
    }
}
