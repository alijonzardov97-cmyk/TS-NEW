package com.ts.messenger.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

sealed interface SocketEvent {
    /** Authenticated and subscribed; messages can be sent. */
    data object Ready : SocketEvent
    data class Incoming(val message: MessageDto) : SocketEvent
    /** The server accepted one of our messages. */
    data class Sent(val id: String, val channelId: String, val createdAt: String) : SocketEvent
    data object Closed : SocketEvent
}

/**
 * WebSocket connection to the server. Uses the same pinned OkHttp client as the REST calls, so
 * the certificate pin applies here too. Reconnects with backoff while started.
 */
class ChatSocket(
    pinnedClient: OkHttpClient,
    private val baseUrl: HttpUrl,
    private val scope: CoroutineScope,
    private val tokenProvider: suspend () -> String?,
    private val channelIds: () -> List<String>,
) {
    // A long-lived socket must not inherit the REST call timeouts.
    private val client = pinnedClient.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val _events = MutableSharedFlow<SocketEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<SocketEvent> = _events.asSharedFlow()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var ready = false
    private var loop: Job? = null

    val isReady: Boolean get() = ready

    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            var attempt = 0
            while (isActive) {
                val opened = connectOnce()
                attempt = if (opened) 0 else minOf(attempt + 1, 6)
                delay(minOf(30_000L, 1_000L shl attempt))
            }
        }
    }

    fun stop() {
        loop?.cancel()
        loop = null
        ready = false
        socket?.close(1000, null)
        socket = null
    }

    /** Subscribes to a conversation created while connected. */
    fun subscribe(channelId: String) {
        if (!ready) return
        socket?.send(buildJsonObject {
            put("type", "subscribe")
            put("channel_ids", JsonArray(listOf(JsonPrimitive(channelId))))
        }.toString())
    }

    /** Returns false if the socket is not ready, in which case nothing was sent. */
    fun sendMessage(channelId: String, ciphertext: List<Int>, nonce: List<Int>, messageType: String = "text"): Boolean {
        val ws = socket
        if (!ready || ws == null) return false
        return ws.send(buildJsonObject {
            put("type", "send_message")
            put("channel_id", channelId)
            put("ciphertext", JsonArray(ciphertext.map { JsonPrimitive(it) }))
            put("nonce", JsonArray(nonce.map { JsonPrimitive(it) }))
            put("message_type", messageType)
        }.toString())
    }

    /** One connection lifetime. Returns true if it got as far as authenticating. */
    private suspend fun connectOnce(): Boolean {
        val token = try { tokenProvider() } catch (_: Exception) { null } ?: return false
        val closed = kotlinx.coroutines.CompletableDeferred<Unit>()
        var authenticated = false
        val request = Request.Builder().url(baseUrl.newBuilder().encodedPath("/ws").build()).build()
        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(buildJsonObject {
                    put("type", "authenticate")
                    put("token", token)
                }.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.length > MAX_FRAME_CHARS) return
                val obj = runCatching { AppJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                when (obj.str("type")) {
                    "authenticated" -> {
                        authenticated = true
                        ready = true
                        val ids = channelIds()
                        if (ids.isNotEmpty()) {
                            webSocket.send(buildJsonObject {
                                put("type", "subscribe")
                                put("channel_ids", JsonArray(ids.map { JsonPrimitive(it) }))
                            }.toString())
                        }
                        _events.tryEmit(SocketEvent.Ready)
                    }
                    "new_message" -> runCatching { AppJson.decodeFromJsonElement(MessageDto.serializer(), obj) }
                        .getOrNull()?.let { _events.tryEmit(SocketEvent.Incoming(it)) }
                    "message_sent" -> {
                        val id = obj.str("id"); val ch = obj.str("channel_id"); val at = obj.str("created_at")
                        if (id != null && ch != null && at != null) _events.tryEmit(SocketEvent.Sent(id, ch, at))
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                closed.complete(Unit)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                closed.complete(Unit)
            }
        })
        socket = ws
        // Application-level keepalive in addition to WebSocket pings.
        val heartbeat = scope.launch {
            while (isActive) {
                delay(25_000)
                ws.send(buildJsonObject {
                    put("type", "ping")
                    put("timestamp", System.currentTimeMillis())
                }.toString())
            }
        }
        try {
            closed.await()
        } finally {
            heartbeat.cancel()
            ready = false
            if (socket === ws) socket = null
            ws.cancel()
            _events.tryEmit(SocketEvent.Closed)
        }
        return authenticated
    }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private companion object {
        const val MAX_FRAME_CHARS = 2_000_000
    }
}
