package com.ts.messenger

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ts.messenger.chat.ChatLog
import com.ts.messenger.chat.ChatRepository
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
)

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
        _state.update { it.copy(unlocked = false) }
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
        launchBusy {
            store.putString(K_SERVER_URL, probe.baseUrl)
            store.putString(K_SERVER_PINS, AppJson.encodeToString(probe.pins))
            useServer(url, probe.pins)
            val config = api!!.config()
            _state.update { it.copy(screen = Screen.Login, config = config) }
        }
    }

    fun cancelPin() = _state.update { it.copy(screen = Screen.Connect, error = null) }

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
                        email = email.trim(),
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
        stopChat()
        store.wipeAll()
        api = null
        baseUrl = null
        pins = emptyList()
        _state.update { UiState(unlocked = true, screen = Screen.Connect) }
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
        val r = ChatRepository(a, ChatCrypto(store, keyVault), chatLog, sock, me.id)
        repo = r
        socket = sock
        chatJob = viewModelScope.launch {
            launch {
                // Sequential on purpose: incoming messages are decrypted in arrival order.
                sock.events.collect { e ->
                    when (e) {
                        SocketEvent.Ready -> _state.update { it.copy(connected = true) }
                        SocketEvent.Closed -> _state.update { it.copy(connected = false) }
                        is SocketEvent.Incoming -> runCatching { r.onIncoming(e.message) }
                        is SocketEvent.Sent -> r.onSent(e.id, e.channelId, e.createdAt)
                    }
                }
            }
            launch {
                r.changed.collect { ch ->
                    if (_state.value.current?.channel?.id == ch) {
                        _state.update { it.copy(messages = r.cached(ch)) }
                    }
                }
            }
            launch { r.identityAlerts.collect { al -> _state.update { it.copy(identityAlert = al) } } }
        }
        sock.start()
        refreshDms()
    }

    private fun stopChat() {
        chatJob?.cancel()
        chatJob = null
        socket?.stop()
        socket = null
        repo = null
        dmIds = emptyList()
    }

    fun refreshDms() {
        val a = api ?: return
        viewModelScope.launch {
            try {
                val dms = a.listDms()
                repo?.registerDms(dms)
                dmIds = dms.map { it.channel.id }
                socket?.let { s -> dms.forEach { s.subscribe(it.channel.id) } }
                _state.update { it.copy(dms = dms) }
            } catch (e: CertificateChangedException) {
                presentCertChange()
            } catch (_: Exception) {
                // Transient (offline); the list keeps what it had.
            }
        }
    }

    fun openChat(dm: DmChannel) {
        val r = repo ?: return
        _state.update { it.copy(screen = Screen.Chat, current = dm, messages = r.cached(dm.channel.id), error = null) }
        viewModelScope.launch {
            try {
                r.loadHistory(dm)
            } catch (e: CertificateChangedException) {
                presentCertChange()
            } catch (e: Exception) {
                _state.update { it.copy(error = errorRes(e)) }
            }
        }
    }

    fun closeChat() {
        _state.update { it.copy(screen = Screen.Home, current = null, messages = emptyList(), error = null) }
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

    fun acceptIdentity() {
        val alert = _state.value.identityAlert ?: return
        val r = repo ?: return
        viewModelScope.launch {
            r.acceptIdentity(alert)
            _state.update { it.copy(identityAlert = null) }
            _state.value.current?.let { runCatching { r.loadHistory(it) } }
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
        const val MAX_TEXT_CHARS = 3000
    }
}
