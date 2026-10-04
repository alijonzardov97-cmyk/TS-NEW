package com.ts.messenger

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ts.messenger.chat.ChatLog
import com.ts.messenger.chat.ChatRepository
import com.ts.messenger.call.CallManager
import com.ts.messenger.call.CallPhase
import com.ts.messenger.call.CallUi
import com.ts.messenger.files.FileService
import com.ts.messenger.files.FileTooLargeException
import com.ts.messenger.net.FileRef
import com.ts.messenger.chat.NotConnectedException
import com.ts.messenger.crypto.ChatCrypto
import com.ts.messenger.crypto.KeyVault
import com.ts.messenger.crypto.PeerIdentityChangedException
import com.ts.messenger.net.ChatMessage
import com.ts.messenger.net.ChatSocket
import com.ts.messenger.net.DmChannel
import com.ts.messenger.net.KeyRegistrationRequest
import com.ts.messenger.net.Session
import com.ts.messenger.net.SocketEvent
import com.ts.messenger.push.PushRegistry
import kotlinx.coroutines.withTimeoutOrNull
import org.unifiedpush.android.connector.UnifiedPush
import com.ts.messenger.net.ApiException
import com.ts.messenger.net.AppJson
import com.ts.messenger.net.CertificateChangedException
import com.ts.messenger.net.HttpClientFactory
import com.ts.messenger.net.LoginRequest
import com.ts.messenger.net.NetworkException
import com.ts.messenger.net.RegisterRequest
import com.ts.messenger.net.ServerConfig
import com.ts.messenger.net.ServerProbe
import com.ts.messenger.net.TsApi
import com.ts.messenger.net.UserPublic
import com.ts.messenger.net.parseServerUrl
import com.ts.messenger.security.SecureStore
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class PushStatus { Off, On, NoDistributor, ServerUnsupported }

sealed interface Screen {
    data object Connect : Screen
    data class ConfirmPin(val probe: ServerProbe) : Screen
    data object Login : Screen
    data object Register : Screen
    data class Recovery(val code: String) : Screen
    data object Home : Screen
    data object NewChat : Screen
    data object Chat : Screen
}

/** A server whose certificate no longer matches the saved pin; waits for the user's decision. */
data class CertChange(val oldFingerprint: String, val probe: ServerProbe)

data class UiState(
    val unlocked: Boolean = false,
    val screen: Screen = Screen.Connect,
    val busy: Boolean = false,
    @StringRes val error: Int? = null,
    val serverHost: String? = null,
    val config: ServerConfig? = null,
    val user: UserPublic? = null,
    val certChange: CertChange? = null,
    val dms: List<DmChannel> = emptyList(),
    val current: DmChannel? = null,
    val messages: List<ChatMessage> = emptyList(),
    val connected: Boolean = false,
    val identityAlert: PeerIdentityChangedException? = null,
    val searchResults: List<UserPublic> = emptyList(),
    @StringRes val notice: Int? = null,
    val push: PushStatus = PushStatus.Off,
    /** Background connection (foreground service) switched on by the user; on by default. */
    val background: Boolean = true,
    /** True while the signed-in user is moving the app to another server address. */
    val movingServer: Boolean = false,
    val showSecurity: Boolean = false,
    /** Auto-delete of messages on this phone, in days (0 = never). */
    val ttlDays: Int = 0,
    /** 0 = no dialog, 1 = delete all chats, 2 = wipe everything. */
    val confirmWipe: Int = 0,
    /** Notifications show only "new message", without the sender's name. */
    val hideSender: Boolean = true,
    /** Share my "online" status with chat partners who share theirs (off by default). */
    val showPresence: Boolean = false,
    /** Partners who share their status and are online now (empty unless showPresence). */
    val onlinePeers: Set<String> = emptySet(),
    /** Whether the open chat's peer key was compared with the person (and still matches). */
    val trust: Trust = Trust.Unverified,
    /** Channels whose newest incoming message has not been seen yet (shown as a dot). */
    val unread: Set<String> = emptySet(),
    /** Channel id -> time of its newest message; the list shows the most recent chat on top. */
    val lastActivity: Map<String, String> = emptyMap(),
    val uploading: Boolean = false,
    val call: CallUi = CallUi(),
    val safety: SafetyInfo? = null,
)

enum class Trust { Unverified, Verified, Changed }

/** [number] is null while the peer's identity key is not known yet (no message exchanged). */
data class SafetyInfo(val number: String?)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SecureStore(app)
    private val keyVault = KeyVault(store)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var baseUrl: HttpUrl? = null
    private var pins: List<String> = emptyList()
    private var api: TsApi? = null
    private var restored = false

    private val chatLog = ChatLog(store)
    private var repo: ChatRepository? = null
    private var fileSvc: FileService? = null
    private var calls: CallManager? = null
    private var pendingCallChannel: String? = null
    private var pendingChatChannel: String? = null
    private val thumbs = android.util.LruCache<String, android.graphics.Bitmap>(8)
    private var socket: ChatSocket? = null
    private var chatJob: Job? = null
    private var dmIds: List<String> = emptyList()

    // ── App lock ──

    /** Called after biometrics / device credential succeeded. */
    fun onUnlocked() {
        if (_state.value.unlocked) return
        _state.update { it.copy(unlocked = true) }
        // Restore the saved session only once; later unlocks keep the current screen.
        if (!restored) {
            restored = true
            restoreSession()
        }
    }

    /** Called when the app goes to the background: everything is hidden again. */
    fun lock() {
        // During a call only the call screen is reachable; the app locks as soon as it ends.
        if (_state.value.call.phase != CallPhase.Idle) { lockAfterCall = true; return }
        _state.update { it.copy(unlocked = false) }
    }

    private var lockAfterCall = false
    private var lastTone: android.media.Ringtone? = null

    /**
     * A message arrived over the live socket: play the notification sound while the app is open,
     * or raise a system notification (metadata only) while it is locked in the background.
     */
    private fun notifyIncoming(m: com.ts.messenger.net.MessageDto) {
        val sender = m.senderId ?: return
        if (sender == _state.value.user?.id) return
        if (m.messageType != "text" && m.messageType != "file") return
        val app = getApplication<Application>()
        if (_state.value.unlocked) {
            runCatching {
                val uri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
                lastTone?.stop()
                lastTone = android.media.RingtoneManager.getRingtone(app, uri)?.also { it.play() }
            }
        } else if (com.ts.messenger.push.Notifications.claim(m.id)) {
            val name = _state.value.dms.firstOrNull { it.channel.id == m.channelId }?.otherUser?.displayName.orEmpty()
            com.ts.messenger.push.Notifications.showNewMessage(
                app, com.ts.messenger.net.PushPayload("new_message", name, m.channelId),
            )
        }
    }

    private fun restoreSession() {
        val url = store.getString(K_SERVER_URL)?.toHttpUrlOrNull()
        val savedPins = store.getString(K_SERVER_PINS)
            ?.let { runCatching { AppJson.decodeFromString<List<String>>(it) }.getOrNull() }
            .orEmpty()
        if (url == null || savedPins.isEmpty()) {
            _state.update { it.copy(screen = Screen.Connect) }
            return
        }
        useServer(url, savedPins)
        val user = store.getString(K_USER)
            ?.let { runCatching { AppJson.decodeFromString<UserPublic>(it) }.getOrNull() }
        val hasToken = store.get(K_REFRESH)?.also { it.fill(0) } != null
        _state.update {
            if (hasToken && user != null) it.copy(screen = Screen.Home, user = user)
            else it.copy(screen = Screen.Login)
        }
        if (hasToken && user != null) startChat()
    }

    private fun useServer(url: HttpUrl, serverPins: List<String>) {
        baseUrl = url
        pins = serverPins
        val a = TsApi(url, HttpClientFactory.create(url.host, serverPins))
        a.session = Session(store) { refreshToken -> a.refresh(refreshToken) }
        api = a
        _state.update { it.copy(serverHost = url.host) }
    }

    // ── Connecting to a server ──

    fun connect(input: String) {
        val url = parseServerUrl(input)
        if (url == null) {
            _state.update { it.copy(error = R.string.error_https_only) }
            return
        }
        launchBusy {
            val probe = TsApi.probe(url)
            _state.update { it.copy(screen = Screen.ConfirmPin(probe)) }
        }
    }

    fun confirmPin(probe: ServerProbe) {
        val url = probe.baseUrl.toHttpUrlOrNull() ?: return
        if (_state.value.movingServer) { confirmMove(probe, url); return }
        launchBusy {
            store.putString(K_SERVER_URL, probe.baseUrl)
            store.putString(K_SERVER_PINS, AppJson.encodeToString(probe.pins))
            useServer(url, probe.pins)
            val config = api!!.config()
            _state.update { it.copy(screen = Screen.Login, config = config) }
        }
    }

    fun cancelPin() = _state.update { it.copy(screen = Screen.Connect, error = null) }

    /** Menu: point the signed-in app at a new address; the login and encryption keys stay. */
    fun startMoveServer() = _state.update { it.copy(movingServer = true, screen = Screen.Connect, error = null) }

    fun cancelMoveServer() = _state.update { it.copy(movingServer = false, screen = Screen.Home, error = null) }

    /**
     * The user compared the new fingerprint. Only now do tokens go to the new address; an
     * unauthenticated request first checks that a TS server answers there, and on any failure
     * the old address stays in place.
     */
    private fun confirmMove(probe: ServerProbe, url: HttpUrl) {
        val oldUrl = baseUrl
        val oldPins = pins
        launchBusy {
            try {
                useServer(url, probe.pins)
                api!!.config()
            } catch (e: Exception) {
                if (oldUrl != null) useServer(oldUrl, oldPins)
                throw e
            }
            store.putString(K_SERVER_URL, probe.baseUrl)
            store.putString(K_SERVER_PINS, AppJson.encodeToString(probe.pins))
            stopChat()
            com.ts.messenger.push.ListenService.stop(getApplication<Application>()) // restarted by startChat() with the new address
            _state.update { it.copy(movingServer = false, screen = Screen.Home, connected = false) }
            startChat()
        }
    }

    fun changeServer() {
        store.remove(K_SERVER_URL)
        store.remove(K_SERVER_PINS)
        api = null
        baseUrl = null
        pins = emptyList()
        _state.update { it.copy(screen = Screen.Connect, serverHost = null, config = null, error = null) }
    }

    // ── Account ──

    fun goTo(screen: Screen) {
        _state.update { it.copy(screen = screen, error = null) }
        if (screen == Screen.Register && _state.value.config == null) {
            viewModelScope.launch { runCatching { api?.config() }.getOrNull()?.let { c -> _state.update { s -> s.copy(config = c) } } }
        }
    }

    fun login(username: String, password: String, totp: String?) {
        val client = api ?: return
        launchBusy {
            val res = client.login(LoginRequest(username.trim(), password, totp?.trim()?.ifEmpty { null }))
            saveSession(res.accessToken, res.refreshToken, res.user)
            try {
                ensureKeys(client)
            } catch (e: Exception) {
                store.remove(K_ACCESS)
                store.remove(K_REFRESH)
                throw e
            }
            _state.update { it.copy(screen = Screen.Home, user = res.user) }
            startChat()
        }
    }

    fun register(
        username: String, email: String, displayName: String,
        password: String, confirm: String, inviteCode: String,
    ) {
        val client = api ?: return
        if (!passwordMeetsRules(password)) {
            _state.update { it.copy(error = R.string.error_pw_rules) }
            return
        }
        if (password != confirm) {
            _state.update { it.copy(error = R.string.error_pw_mismatch) }
            return
        }
        launchBusy {
            // Key generation is CPU work done in Rust; keep it off the main thread.
            val keys = withContext(Dispatchers.Default) { keyVault.generateRegistrationKeys() }
            try {
                val res = client.register(
                    RegisterRequest(
                        username = username.trim(),
                        // The server still requires an email; the app no longer asks for one, so use a private placeholder.
                        email = email.trim().ifEmpty { "${username.trim().lowercase()}@ts.invalid" },
                        password = password,
                        displayName = displayName.trim().ifEmpty { username.trim() },
                        identityKey = keys.identityKey,
                        signedPrekey = keys.signedPrekey,
                        oneTimePrekeys = keys.oneTimePrekeys,
                        inviteCode = inviteCode.trim().ifEmpty { null },
                    ),
                )
                saveSession(res.accessToken, res.refreshToken, res.user)
                _state.update {
                    it.copy(
                        user = res.user,
                        screen = res.recoveryCode?.let { c -> Screen.Recovery(c) } ?: Screen.Home,
                    )
                }
                if (res.recoveryCode == null) startChat()
            } catch (e: Exception) {
                keyVault.wipe() // never keep keys the server does not know about
                throw e
            }
        }
    }

    fun recoverySaved() {
        _state.update { it.copy(screen = Screen.Home) }
        startChat()
    }

    fun signOut() {
        val a = api
        val app = getApplication<Application>()
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            // Stop the server from sending notifications to this device for the account that is
            // leaving; never let a slow network block signing out for long.
            if (a != null) {
                withTimeoutOrNull(5_000) { runCatching { PushRegistry(store).unsubscribe(a) } }
            }
            runCatching { UnifiedPush.unregister(app) }
            com.ts.messenger.push.ListenService.stop(app)
            stopChat()
            store.wipeAll()
            FileService.wipe(app)
            thumbs.evictAll()
            com.ts.messenger.ui.AvatarCache.clear()
            fileSvc = null
            api = null
            baseUrl = null
            pins = emptyList()
            _state.value = UiState(unlocked = true, screen = Screen.Connect)
        }
    }

    // ── Security menu ──

    fun openSecurity() = _state.update { it.copy(showSecurity = true, hideSender = store.getString(K_HIDE) != "0", showPresence = presenceOn(), ttlDays = ttlSetting()) }

    private fun ttlSetting(): Int = store.getString(K_TTL)?.toIntOrNull()?.takeIf { it in setOf(0, 1, 7, 30) } ?: 0

    fun setTtl(days: Int) {
        if (days !in setOf(0, 1, 7, 30)) return
        store.putString(K_TTL, days.toString())
        _state.update { it.copy(ttlDays = days) }
        applyTtl()
    }

    /** Applies the auto-delete setting to every conversation and refreshes the open one. */
    private fun applyTtl() {
        val r = repo ?: return
        r.ttlDays = ttlSetting()
        r.clearedBefore = store.getString(K_CLEARED)?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
        _state.value.dms.forEach { r.purgeExpired(it.channel.id) }
        _state.value.current?.let { cur -> _state.update { it.copy(messages = r.cached(cur.channel.id)) } }
    }

    fun dismissSecurity() = _state.update { it.copy(showSecurity = false) }

    private fun presenceOn(): Boolean = store.getString(K_PRESENCE) == "1"

    private fun sendPresence(on: Boolean) {
        socket?.sendJson(kotlinx.serialization.json.buildJsonObject {
            put("type", kotlinx.serialization.json.JsonPrimitive("update_presence"))
            put("status", kotlinx.serialization.json.JsonPrimitive(if (on) "online" else "offline"))
        })
    }

    fun togglePresence() {
        val on = !_state.value.showPresence
        store.putString(K_PRESENCE, if (on) "1" else "0")
        _state.update { it.copy(showPresence = on, onlinePeers = if (on) it.onlinePeers else emptySet()) }
        sendPresence(on)
    }

    fun toggleHideSender() {
        val hide = !_state.value.hideSender
        store.putString(K_HIDE, if (hide) "1" else "0")
        _state.update { it.copy(hideSender = hide) }
    }

    fun askWipe(mode: Int) = _state.update { it.copy(confirmWipe = mode) }

    fun cancelWipe() = _state.update { it.copy(confirmWipe = 0) }

    /**
     * Deletes every conversation from this phone but keeps the account, keys and login. Messages
     * that are still on the server are not shown again.
     */
    fun wipeChats() {
        val r = repo ?: return
        val app = getApplication<Application>()
        val newest = _state.value.dms
            .flatMap { r.cached(it.channel.id) }
            .mapNotNull { runCatching { java.time.OffsetDateTime.parse(it.createdAt).toInstant() }.getOrNull() }
            .maxOrNull() ?: java.time.Instant.now()
        store.putString(K_CLEARED, newest.toString())
        r.clearedBefore = newest
        _state.value.dms.forEach { r.purgeExpired(it.channel.id) }
        FileService.wipe(app)
        thumbs.evictAll()
        _state.update { it.copy(messages = emptyList(), lastActivity = emptyMap(), unread = emptySet(), confirmWipe = 0) }
    }

    /**
     * Emergency wipe: everything on this phone (keys, messages, files, server address, login) is
     * deleted at once, without waiting for the network. The account itself stays on the server.
     */
    fun panicWipe() {
        val app = getApplication<Application>()
        runCatching { UnifiedPush.unregister(app) }
        com.ts.messenger.push.ListenService.stop(app)
        stopChat()
        store.wipeAll()
        FileService.wipe(app)
        thumbs.evictAll()
        com.ts.messenger.ui.AvatarCache.clear()
        fileSvc = null
        api = null
        baseUrl = null
        pins = emptyList()
        seen = null
        _state.value = UiState(unlocked = true, screen = Screen.Connect)
    }

    // ── Profile picture ──

    private fun loadAvatars(users: List<UserPublic>) {
        val a = api ?: return
        users.forEach { u ->
            val url = u.avatarUrl ?: return@forEach
            if (com.ts.messenger.ui.AvatarCache.urls[u.id] == url) return@forEach
            com.ts.messenger.ui.AvatarCache.urls[u.id] = url
            viewModelScope.launch {
                try {
                    val bytes = a.fetchAvatar(url)
                    val bmp = withContext(Dispatchers.Default) { com.ts.messenger.ui.decodeAvatar(bytes, 256) } ?: return@launch
                    com.ts.messenger.ui.AvatarCache.images[u.id] = bmp.asImageBitmap()
                } catch (_: Exception) {
                    com.ts.messenger.ui.AvatarCache.urls.remove(u.id)
                }
            }
        }
    }

    /** The user picked a photo: crop it to a square, strip metadata, upload it as the profile picture. */
    fun uploadAvatar(uri: android.net.Uri) {
        val a = api ?: return
        val app = getApplication<Application>()
        launchBusy {
            val jpeg = withContext(Dispatchers.IO) {
                val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw java.io.IOException("cannot read the image")
                if (bytes.size > 25_000_000) throw java.io.IOException("image too large")
                val bmp = com.ts.messenger.ui.decodeAvatar(bytes, 512) ?: throw java.io.IOException("not an image")
                java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
            }
            val updated = a.uploadAvatar(jpeg)
            store.putString(K_USER, AppJson.encodeToString(updated))
            com.ts.messenger.ui.AvatarCache.urls.remove(updated.id)
            _state.update { it.copy(user = updated, notice = R.string.notice_avatar) }
            loadAvatars(listOf(updated))
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    // ── Chats ──

    private suspend fun ensureKeys(a: com.ts.messenger.net.TsApi) {
        if (keyVault.hasKeys()) return
        // First sign-in on this device for an existing account: create and upload new keys.
        val keys = withContext(Dispatchers.Default) { keyVault.generateRegistrationKeys() }
        try {
            a.registerKeys(KeyRegistrationRequest(keys.identityKey, keys.signedPrekey, keys.oneTimePrekeys))
        } catch (e: Exception) {
            keyVault.wipe()
            throw e
        }
        _state.update { it.copy(notice = R.string.notice_new_keys) }
    }

    @Volatile private var iceCfg: List<org.webrtc.PeerConnection.IceServer> = emptyList()

    private fun loadIceServers(a: TsApi) {
        viewModelScope.launch {
            runCatching { a.iceServers() }.getOrNull()?.let { list ->
                iceCfg = list.map { s ->
                    val b = org.webrtc.PeerConnection.IceServer.builder(s.urls)
                    if (s.username != null && s.credential != null) b.setUsername(s.username).setPassword(s.credential)
                    b.createIceServer()
                }
            }
        }
    }

    private fun startChat() {
        val a = api ?: return
        val url = baseUrl ?: return
        val me = _state.value.user ?: return
        stopChat()
        val sock = ChatSocket(
            HttpClientFactory.create(url.host, pins), url, viewModelScope,
            tokenProvider = { a.session?.accessToken() },
            channelIds = { dmIds },
        )
        val fs = FileService(getApplication<Application>(), a)
        fileSvc = fs
        val r = ChatRepository(a, ChatCrypto(store, keyVault), chatLog, sock, me.id, fs)
        r.ttlDays = ttlSetting()
        r.clearedBefore = store.getString(K_CLEARED)?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
        val cm = CallManager(getApplication<Application>(), viewModelScope, sock, me.id, iceServers = { iceCfg }) { ch ->
            _state.value.dms.firstOrNull { it.channel.id == ch }?.let { it.otherUser.id to it.otherUser.displayName }
        }
        calls = cm
        repo = r
        socket = sock
        chatJob = viewModelScope.launch {
            launch {
                // Sequential on purpose: incoming messages are decrypted in arrival order.
                sock.events.collect { e ->
                    when (e) {
                        SocketEvent.Ready -> { _state.update { it.copy(connected = true) }; replenishKeys(); if (presenceOn()) sendPresence(true) }
                        SocketEvent.Closed -> _state.update { it.copy(connected = false, onlinePeers = emptySet()) }
                        is SocketEvent.Presence -> if (presenceOn()) _state.update { s ->
                            s.copy(onlinePeers = if (e.online) s.onlinePeers + e.userId else s.onlinePeers - e.userId)
                        }
                        is SocketEvent.PresenceBulk -> if (presenceOn()) _state.update { it.copy(onlinePeers = e.onlineIds.toSet()) }
                        is SocketEvent.Incoming -> {
                            runCatching { r.onIncoming(e.message) }
                            notifyIncoming(e.message)
                        }
                        is SocketEvent.Sent -> r.onSent(e.id, e.channelId, e.createdAt)
                        is SocketEvent.Voice -> cm.onEvent(e.type, e.data)
                    }
                }
            }
            launch {
                r.changed.collect { ch ->
                    val msgs = r.cached(ch)
                    msgs.lastOrNull()?.let { last -> _state.update { it.copy(lastActivity = it.lastActivity + (ch to last.createdAt)) } }
                    if (_state.value.current?.channel?.id == ch) {
                        _state.update { it.copy(messages = msgs) }
                        updateTrust()
                        markSeen(ch)
                    } else recomputeUnread()
                }
            }
            launch {
                cm.ui.collect { c ->
                    _state.update { it.copy(call = c) }
                    if (c.phase == CallPhase.Idle && lockAfterCall) { lockAfterCall = false; _state.update { it.copy(unlocked = false) } }
                }
            }
            launch { r.identityAlerts.collect { al -> _state.update { it.copy(identityAlert = al) } } }
        }
        sock.start()
        loadIceServers(a)
        refreshDms()
        syncPush()
        val on = bgEnabled()
        _state.update { it.copy(background = on) }
        if (on) com.ts.messenger.push.ListenService.start(getApplication<Application>())
    }

    private fun bgEnabled() = store.getString(K_BG) != "0"

    /** Switches the always-on background connection (and with it, notifications when closed). */
    fun toggleBackground() {
        val on = !bgEnabled()
        store.putString(K_BG, if (on) "1" else "0")
        val app = getApplication<Application>()
        if (on) com.ts.messenger.push.ListenService.start(app) else com.ts.messenger.push.ListenService.stop(app)
        _state.update { it.copy(background = on) }
    }

    private fun stopChat() {
        calls?.shutdown()
        calls = null
        chatJob?.cancel()
        chatJob = null
        socket?.stop()
        socket = null
        repo = null
        dmIds = emptyList()
    }

    // ── Notifications (UnifiedPush) ──

    private fun syncPush() {
        val a = api ?: return
        val app = getApplication<Application>()
        viewModelScope.launch {
            runCatching { PushRegistry(store).sync(a) }
            val hasEndpoint = PushRegistry(store).load() != null
            val status = when {
                hasEndpoint && UnifiedPush.getAckDistributor(app) != null -> PushStatus.On
                UnifiedPush.getDistributors(app).isEmpty() -> PushStatus.NoDistributor
                else -> PushStatus.Off
            }
            _state.update { it.copy(push = status) }
        }
    }

    /** Called once the notification permission question has been answered. */
    fun enablePush() {
        val a = api ?: return
        val app = getApplication<Application>()
        viewModelScope.launch {
            val distributors = UnifiedPush.getDistributors(app)
            if (distributors.isEmpty()) {
                _state.update { it.copy(push = PushStatus.NoDistributor) }
                return@launch
            }
            val vapid = try {
                a.vapidKey()
            } catch (e: ApiException) {
                _state.update { it.copy(push = if (e.status == 404) PushStatus.ServerUnsupported else it.push, error = if (e.status == 404) null else R.string.error_generic) }
                return@launch
            } catch (e: CertificateChangedException) {
                presentCertChange()
                return@launch
            } catch (e: Exception) {
                _state.update { it.copy(error = errorRes(e)) }
                return@launch
            }
            // Passing the server's VAPID key lets the distributor reject pushes from anyone else.
            UnifiedPush.saveDistributor(app, distributors.first())
            UnifiedPush.register(app, vapid = vapid)
            _state.update { it.copy(push = PushStatus.On, error = null) }
        }
    }

    fun disablePush() {
        val a = api
        val app = getApplication<Application>()
        viewModelScope.launch {
            if (a != null) withTimeoutOrNull(5_000) { runCatching { PushRegistry(store).unsubscribe(a) } }
            runCatching { UnifiedPush.unregister(app) }
            PushRegistry(store).clear()
            _state.update { it.copy(push = PushStatus.Off) }
        }
    }

    /** channel id -> time of the newest incoming message the user has seen. */
    private var seen: MutableMap<String, String>? = null
    private val seenSerializer = kotlinx.serialization.builtins.MapSerializer(
        kotlinx.serialization.serializer<String>(), kotlinx.serialization.serializer<String>(),
    )

    private fun newestIncoming(ch: String): String? {
        val me = _state.value.user?.id
        return repo?.cached(ch)?.lastOrNull { it.senderId != me }?.createdAt
    }

    private fun seenMap(): MutableMap<String, String> = seen ?: run {
        val raw = store.getString(K_SEEN)
        val m = raw?.let { runCatching { AppJson.decodeFromString(seenSerializer, it) }.getOrNull() }?.toMutableMap()
            // First start with this feature: everything already on the device counts as seen.
            ?: _state.value.dms.mapNotNull { d -> newestIncoming(d.channel.id)?.let { d.channel.id to it } }.toMap().toMutableMap()
        seen = m
        if (raw == null) store.putString(K_SEEN, AppJson.encodeToString(seenSerializer, m))
        m
    }

    private fun markSeen(ch: String) {
        val t = newestIncoming(ch) ?: return
        val m = seenMap()
        if (m[ch] != t) {
            m[ch] = t
            store.putString(K_SEEN, AppJson.encodeToString(seenSerializer, m))
        }
        recomputeUnread()
    }

    private fun recomputeUnread() {
        val m = seenMap()
        val open = _state.value.current?.channel?.id
        val u = _state.value.dms.map { it.channel.id }.filter { ch ->
            ch != open && newestIncoming(ch)?.let { it > (m[ch] ?: "") } == true
        }.toSet()
        _state.update { it.copy(unread = u) }
    }

    fun refreshDms() {
        val a = api ?: return
        viewModelScope.launch {
            try {
                val dms = a.listDms()
                repo?.registerDms(dms)
                dmIds = dms.map { it.channel.id }
                socket?.let { s -> dms.forEach { s.subscribe(it.channel.id) } }
                val activity = dms.mapNotNull { d -> repo?.cached(d.channel.id)?.lastOrNull()?.let { d.channel.id to it.createdAt } }.toMap()
                _state.update { it.copy(dms = dms, lastActivity = it.lastActivity + activity) }
                loadAvatars(dms.map { it.otherUser } + listOfNotNull(_state.value.user))
                applyTtl()
                seenMap()
                recomputeUnread()
                // Pick up what arrived while the app was closed, so order and dots are right.
                val open = _state.value.current?.channel?.id
                dms.filter { it.channel.id != open }.forEach { d ->
                    runCatching { repo?.loadHistory(d) }
                }
                recomputeUnread()
                pendingCallChannel?.let { ch -> pendingCallChannel = null; calls?.ringFromPush(ch) }
                pendingChatChannel?.let { ch ->
                    pendingChatChannel = null
                    if (_state.value.user != null && _state.value.screen != Screen.Chat) dms.firstOrNull { it.channel.id == ch }?.let { openChat(it) }
                }
            } catch (e: CertificateChangedException) {
                presentCertChange()
            } catch (_: Exception) {
                // Transient (offline); the list keeps what it had.
            }
        }
    }

    fun openChat(dm: DmChannel) {
        val r = repo ?: return
        com.ts.messenger.push.Notifications.cancelForChat(getApplication<Application>(), dm.channel.id)
        _state.update { it.copy(screen = Screen.Chat, current = dm, messages = r.cached(dm.channel.id), error = null, trust = Trust.Unverified) }
        updateTrust()
        viewModelScope.launch {
            try {
                r.loadHistory(dm)
                updateTrust()
                markSeen(dm.channel.id)
            } catch (e: CertificateChangedException) {
                presentCertChange()
            } catch (e: Exception) {
                _state.update { it.copy(error = errorRes(e)) }
            }
        }
    }

    fun closeChat() {
        _state.value.current?.channel?.id?.let { markSeen(it) }
        _state.update { it.copy(screen = Screen.Home, current = null, messages = emptyList(), error = null) }
        recomputeUnread()
        refreshDms()
    }

    fun sendMessage(text: String) {
        val dm = _state.value.current ?: return
        val r = repo ?: return
        val t = text.trim()
        if (t.isEmpty() || t.length > MAX_TEXT_CHARS) return
        viewModelScope.launch {
            try {
                r.send(dm, t)
            } catch (e: PeerIdentityChangedException) {
                _state.update { it.copy(identityAlert = e) }
            } catch (e: NotConnectedException) {
                _state.update { it.copy(error = R.string.error_network) }
            } catch (e: CertificateChangedException) {
                presentCertChange()
            } catch (e: Exception) {
                _state.update { it.copy(error = errorRes(e)) }
            }
        }
    }

    fun sendFile(uri: android.net.Uri) {
        val dm = _state.value.current ?: return
        val r = repo ?: return
        val fs = fileSvc ?: return
        if (_state.value.uploading) return
        _state.update { it.copy(uploading = true, error = null) }
        viewModelScope.launch {
            try {
                r.sendFile(dm, fs.pick(uri))
            } catch (e: PeerIdentityChangedException) {
                _state.update { it.copy(identityAlert = e) }
            } catch (e: NotConnectedException) {
                _state.update { it.copy(error = R.string.error_network) }
            } catch (e: CertificateChangedException) {
                presentCertChange()
            } catch (e: FileTooLargeException) {
                _state.update { it.copy(error = R.string.error_file_too_large) }
            } catch (e: ApiException) {
                _state.update {
                    val m = e.message.orEmpty()
                    it.copy(error = if (e.status == 413 || m.contains("too large", true) || m.contains("quota", true)) R.string.error_file_too_large else errorRes(e))
                }
            } catch (e: NetworkException) {
                _state.update { it.copy(error = R.string.error_network) }
            } catch (e: Exception) {
                _state.update { it.copy(error = R.string.error_file_failed) }
            } finally {
                _state.update { it.copy(uploading = false) }
            }
        }
    }

    fun saveFile(ref: FileRef, uri: android.net.Uri) {
        val fs = fileSvc ?: return
        viewModelScope.launch {
            try {
                fs.saveTo(ref, uri)
                android.widget.Toast.makeText(getApplication<Application>(), R.string.file_saved, android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                _state.update { it.copy(error = R.string.error_file_failed) }
            }
        }
    }

    suspend fun loadImage(ref: FileRef): android.graphics.Bitmap? {
        thumbs.get(ref.id)?.let { return it }
        val fs = fileSvc ?: return null
        return try {
            fs.image(ref)?.also { thumbs.put(ref.id, it) }
        } catch (e: Exception) {
            null
        }
    }

    private var keysChecking = false

    /** Keeps the server stocked with one-time prekeys so new chats can always start. */
    private fun replenishKeys() {
        val a = api ?: return
        if (keysChecking) return
        keysChecking = true
        viewModelScope.launch {
            try {
                if (!keyVault.hasKeys()) return@launch
                if (a.prekeyCount() < 20) {
                    val fresh = withContext(Dispatchers.Default) { keyVault.generateMoreOneTimePrekeys(80) }
                    if (fresh.isNotEmpty()) {
                        try {
                            a.uploadOneTimePrekeys(fresh)
                        } catch (e: Exception) {
                            keyVault.discardOneTimePrekeys(fresh.map { it.keyId }.toSet())
                            throw e
                        }
                    }
                }
            } catch (_: Exception) {
                // retried at the next connection
            } finally {
                keysChecking = false
            }
        }
    }

    fun showSafety() {
        val dm = _state.value.current ?: return
        val keys = repo?.safetyKeys(dm.otherUser.id)
        val number = keys?.let { (mine, theirs) ->
            runCatching { uniffi.ts_crypto_ffi.computeSafetyNumber(mine, theirs) }.getOrNull()
        }
        keys?.let { it.first.fill(0); it.second.fill(0) }
        _state.update { it.copy(safety = SafetyInfo(number)) }
    }

    fun dismissSafety() = _state.update { it.copy(safety = null) }

    // ── Calls ──

    fun startCall() {
        val dm = _state.value.current ?: return
        calls?.startOutgoing(dm.channel.id)
    }

    fun acceptCall() { calls?.accept() }
    fun declineCall() { calls?.decline() }
    fun hangupCall() { calls?.hangup() }
    fun toggleMute() { calls?.toggleMute() }
    fun toggleSpeaker() { calls?.toggleSpeaker() }
    fun toggleCamera() { calls?.toggleCamera() }
    fun switchCamera() { calls?.switchCamera() }
    fun dismissCallNotice() { calls?.clearNotice() }

    /** The user tapped an incoming-call notification. */
    /** The user tapped a message notification. */
    fun openChatFromNotification(channelId: String) {
        val dm = _state.value.dms.firstOrNull { it.channel.id == channelId }
        if (dm != null && repo != null) openChat(dm) else pendingChatChannel = channelId
    }

    fun callFromNotification(channelId: String) {
        val c = calls
        if (c != null && _state.value.dms.any { it.channel.id == channelId }) c.ringFromPush(channelId)
        else pendingCallChannel = channelId
    }

    // ── Verified keys ──

    private fun keyDigest(theirs: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(theirs).joinToString("") { "%02x".format(it) }

    private fun verifiedKey(peerId: String) = "verified.$peerId".takeIf { peerId.matches(Regex("[0-9a-fA-F-]{36}")) }

    /** Recomputes the trust state of [peerId]: verified, never verified, or verified before but the key changed. */
    private fun trustOf(peerId: String): Trust {
        val k = verifiedKey(peerId) ?: return Trust.Unverified
        val keys = repo?.safetyKeys(peerId) ?: return Trust.Unverified
        val theirs = keys.second
        val now = keyDigest(theirs)
        keys.first.fill(0); keys.second.fill(0)
        val mark = store.getString(k) ?: return Trust.Unverified
        return if (mark == now) Trust.Verified else Trust.Changed
    }

    private fun updateTrust() {
        val peer = _state.value.current?.otherUser?.id ?: return
        _state.update { it.copy(trust = trustOf(peer)) }
    }

    /** The user compared the safety number with the person and it matched. */
    fun markVerified() {
        val peer = _state.value.current?.otherUser?.id ?: return
        val k = verifiedKey(peer) ?: return
        val keys = repo?.safetyKeys(peer) ?: return
        store.putString(k, keyDigest(keys.second))
        keys.first.fill(0); keys.second.fill(0)
        updateTrust()
    }

    fun unmarkVerified() {
        val peer = _state.value.current?.otherUser?.id ?: return
        verifiedKey(peer)?.let { store.remove(it) }
        updateTrust()
    }

    fun acceptIdentity() {
        val alert = _state.value.identityAlert ?: return
        val r = repo ?: return
        viewModelScope.launch {
            r.acceptIdentity(alert)
            _state.update { it.copy(identityAlert = null) }
            _state.value.current?.let { runCatching { r.loadHistory(it) } }
            updateTrust()
        }
    }

    fun dismissIdentityAlert() = _state.update { it.copy(identityAlert = null) }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    fun openNewChat() = _state.update { it.copy(screen = Screen.NewChat, searchResults = emptyList(), error = null) }

    fun leaveNewChat() = _state.update { it.copy(screen = Screen.Home, searchResults = emptyList(), error = null) }

    fun search(query: String) {
        val a = api ?: return
        val q = query.trim()
        if (q.length < 2) {
            _state.update { it.copy(searchResults = emptyList()) }
            return
        }
        viewModelScope.launch {
            try {
                val me = _state.value.user?.id
                val found = a.searchUsers(q).filter { it.id != me }
                _state.update { it.copy(searchResults = found, error = null) }
            } catch (e: CertificateChangedException) {
                presentCertChange()
            } catch (e: Exception) {
                _state.update { it.copy(error = errorRes(e)) }
            }
        }
    }

    fun startChatWith(user: UserPublic) {
        val a = api ?: return
        launchBusy {
            val dm = a.createDm(user.id)
            repo?.registerDms(listOf(dm))
            dmIds = (dmIds + dm.channel.id).distinct()
            socket?.subscribe(dm.channel.id)
            openChat(dm)
            refreshDms()
        }
    }

    // ── helpers ──

    private fun saveSession(access: String, refresh: String, user: UserPublic) {
        store.putString(K_ACCESS, access)
        store.putString(K_REFRESH, refresh)
        store.putString(K_USER, AppJson.encodeToString(user))
    }

    /**
     * The pinned certificate no longer matches. Let's Encrypt (used by Tailscale and Caddy) renews
     * certificates regularly, so this is expected from time to time. We fetch the new chain with a
     * normally validated handshake (system trust store only) and ask the user to confirm it.
     * Nothing is re-pinned without an explicit tap, and the old session is kept.
     */
    private suspend fun presentCertChange() {
        val url = baseUrl
        val old = pins.firstOrNull()?.removePrefix("sha256/")
        if (url == null || old == null) {
            _state.update { it.copy(error = R.string.error_cert_changed) }
            return
        }
        try {
            val probe = TsApi.probe(url)
            _state.update { it.copy(certChange = CertChange(old, probe)) }
        } catch (e: Exception) {
            // The new certificate does not even validate: treat as a possible attack.
            _state.update { it.copy(error = R.string.error_cert_changed) }
        }
    }

    fun acceptCertChange() {
        val change = _state.value.certChange ?: return
        val url = change.probe.baseUrl.toHttpUrlOrNull() ?: return
        store.putString(K_SERVER_PINS, AppJson.encodeToString(change.probe.pins))
        useServer(url, change.probe.pins)
        _state.update { it.copy(certChange = null, error = null) }
        if (_state.value.user != null) startChat()
    }

    fun rejectCertChange() = _state.update { it.copy(certChange = null) }

    private fun launchBusy(block: suspend () -> Unit) {
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: CertificateChangedException) {
                presentCertChange()
            } catch (e: Exception) {
                _state.update { it.copy(error = errorRes(e)) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    @StringRes
    private fun errorRes(e: Exception): Int = when (e) {
        is CertificateChangedException -> R.string.error_cert_changed
        is NetworkException -> R.string.error_network
        is ApiException -> when {
            e.code == "not_ts" || e.code == "no_cert" -> R.string.error_not_ts_server
            e.status == 401 -> R.string.error_bad_credentials
            e.status == 429 || e.message.orEmpty().contains("too many", ignoreCase = true) -> R.string.error_too_many
            e.message.orEmpty().contains("invite", ignoreCase = true) -> R.string.error_invite
            e.message.orEmpty().contains("taken", ignoreCase = true) ||
                e.message.orEmpty().contains("exists", ignoreCase = true) -> R.string.error_username_taken
            else -> R.string.error_generic
        }
        else -> R.string.error_generic
    }

    private fun passwordMeetsRules(p: String) =
        p.length in 8..128 && p.any { it.isUpperCase() } && p.any { it.isLowerCase() } &&
            p.any { it.isDigit() } && p.any { !it.isLetterOrDigit() }

    private companion object {
        const val K_SERVER_URL = "server.url"
        const val K_SERVER_PINS = "server.pins"
        const val K_ACCESS = "auth.access"
        const val K_REFRESH = "auth.refresh"
        const val K_USER = "auth.user"
        const val K_BG = "bg.enabled"
        const val K_HIDE = "notif.hide"
        const val K_PRESENCE = "presence.show"
        const val K_TTL = "chat.ttl"
        const val K_CLEARED = "chat.cleared"
        const val K_SEEN = "chat.seen"
        const val MAX_TEXT_CHARS = 3000
    }
}
