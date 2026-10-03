package com.ts.messenger

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ts.messenger.crypto.KeyVault
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
}

data class UiState(
    val unlocked: Boolean = false,
    val screen: Screen = Screen.Connect,
    val busy: Boolean = false,
    @StringRes val error: Int? = null,
    val serverHost: String? = null,
    val config: ServerConfig? = null,
    val user: UserPublic? = null,
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
    }

    private fun useServer(url: HttpUrl, serverPins: List<String>) {
        baseUrl = url
        pins = serverPins
        api = TsApi(url, HttpClientFactory.create(url.host, serverPins))
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
            _state.update { it.copy(screen = Screen.Home, user = res.user) }
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
            } catch (e: Exception) {
                keyVault.wipe() // never keep keys the server does not know about
                throw e
            }
        }
    }

    fun recoverySaved() = _state.update { it.copy(screen = Screen.Home) }

    fun signOut() {
        store.wipeAll()
        api = null
        baseUrl = null
        pins = emptyList()
        _state.update { UiState(unlocked = true, screen = Screen.Connect) }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    // ── helpers ──

    private fun saveSession(access: String, refresh: String, user: UserPublic) {
        store.putString(K_ACCESS, access)
        store.putString(K_REFRESH, refresh)
        store.putString(K_USER, AppJson.encodeToString(user))
    }

    private fun launchBusy(block: suspend () -> Unit) {
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                block()
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
    }
}
