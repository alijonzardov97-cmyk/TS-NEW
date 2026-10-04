package com.ts.messenger.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ts.messenger.R
import com.ts.messenger.Screen
import com.ts.messenger.UiState
import com.ts.messenger.net.ServerProbe

@Composable
fun LockScreen(noLockSet: Boolean, failed: Boolean, onUnlock: () -> Unit) {
    Page(stringResource(R.string.unlock_title)) {
        Text(stringResource(R.string.unlock_subtitle), textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SecurityChip(stringResource(R.string.sec_e2e))
            SecurityChip(stringResource(R.string.sec_pinned))
            SecurityChip(stringResource(R.string.sec_local))
        }
        if (noLockSet) Text(stringResource(R.string.unlock_no_lock), color = MaterialTheme.colorScheme.error)
        else {
            if (failed) Text(stringResource(R.string.unlock_failed), color = MaterialTheme.colorScheme.error)
            PrimaryButton(stringResource(R.string.unlock_button), busy = false, onClick = onUnlock)
        }
    }
}

@Composable
fun ConnectScreen(state: UiState, onConnect: (String) -> Unit, onCancel: (() -> Unit)? = null) {
    var address by rememberSaveable { mutableStateOf("") }
    Page(stringResource(R.string.connect_title)) {
        Text(stringResource(R.string.connect_hint), style = MaterialTheme.typography.bodyMedium)
        PlainField(
            address, { address = it }, stringResource(R.string.server_address),
            keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go,
        )
        ErrorText(state.error)
        PrimaryButton(
            stringResource(if (state.busy) R.string.connecting else R.string.connect_button),
            busy = state.busy, enabled = address.isNotBlank(),
        ) { onConnect(address) }
        if (onCancel != null) LinkButton(stringResource(R.string.pin_cancel), onCancel)
    }
}

@Composable
fun ConfirmPinScreen(probe: ServerProbe, busy: Boolean, error: Int?, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Page(stringResource(R.string.pin_title)) {
        Text(probe.baseUrl, style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.pin_hint), style = MaterialTheme.typography.bodyMedium)
        SelectionContainer {
            Text(
                // Group the base64 fingerprint so it is easy to compare by eye.
                probe.leafFingerprint.chunked(8).joinToString(" "),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        ErrorText(error)
        PrimaryButton(stringResource(R.string.pin_confirm), busy = busy, onClick = onConfirm)
        LinkButton(stringResource(R.string.pin_cancel), onCancel)
    }
}

@Composable
fun LoginScreen(
    state: UiState,
    onLogin: (String, String, String?) -> Unit,
    onRegister: () -> Unit,
    onChangeServer: () -> Unit,
) {
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var totp by rememberSaveable { mutableStateOf("") }
    Page(stringResource(R.string.login_title)) {
        state.serverHost?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        PlainField(username, { username = it }, stringResource(R.string.username))
        SecretField(password, { password = it }, stringResource(R.string.password))
        PlainField(
            totp, { totp = it }, stringResource(R.string.totp_code),
            keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done,
        )
        ErrorText(state.error)
        PrimaryButton(
            stringResource(if (state.busy) R.string.signing_in else R.string.sign_in),
            busy = state.busy, enabled = username.isNotBlank() && password.isNotEmpty(),
        ) {
            onLogin(username, password, totp.ifBlank { null })
            password = "" // do not keep the password in UI state longer than needed
        }
        LinkButton(stringResource(R.string.create_account), onRegister)
        LinkButton(stringResource(R.string.change_server), onChangeServer)
    }
}

@Composable
fun RegisterScreen(
    state: UiState,
    onRegister: (String, String, String, String, String, String) -> Unit,
    onBack: () -> Unit,
) {
    var username by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var displayName by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var invite by rememberSaveable { mutableStateOf("") }
    val needsInvite = state.config?.registrationMode == "invite_only"
    Page(stringResource(R.string.register_title)) {
        if (needsInvite) PlainField(invite, { invite = it }, stringResource(R.string.invite_code))
        PlainField(username, { username = it }, stringResource(R.string.username))
        PlainField(email, { email = it }, stringResource(R.string.email), keyboardType = KeyboardType.Email)
        PlainField(displayName, { displayName = it }, stringResource(R.string.display_name))
        SecretField(password, { password = it }, stringResource(R.string.password))
        SecretField(confirm, { confirm = it }, stringResource(R.string.confirm_password), imeAction = ImeAction.Done)
        Text(stringResource(R.string.password_rules), style = MaterialTheme.typography.bodySmall)
        ErrorText(state.error)
        PrimaryButton(
            stringResource(if (state.busy) R.string.registering else R.string.create_account),
            busy = state.busy,
            enabled = username.isNotBlank() && email.isNotBlank() && password.isNotEmpty() &&
                (!needsInvite || invite.isNotBlank()),
        ) {
            onRegister(username, email, displayName, password, confirm, invite)
            password = ""
            confirm = ""
        }
        LinkButton(stringResource(R.string.have_account), onBack)
    }
}

@Composable
fun RecoveryScreen(code: String, onSaved: () -> Unit) {
    Page(stringResource(R.string.recovery_title)) {
        Text(stringResource(R.string.recovery_body), style = MaterialTheme.typography.bodyMedium)
        SelectionContainer {
            Text(code, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        }
        PrimaryButton(stringResource(R.string.recovery_saved), busy = false, onClick = onSaved)
    }
}

@Composable
fun HomeScreen(state: UiState, onSignOut: () -> Unit) {
    Page(stringResource(R.string.home_title)) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.user?.let { Text(it.displayName, style = MaterialTheme.typography.titleMedium) }
            Text(stringResource(R.string.home_placeholder))
        }
        LinkButton(stringResource(R.string.sign_out), onSignOut)
    }
}

@Composable
fun AppContent(
    state: UiState,
    vm: com.ts.messenger.AppViewModel,
    onEnablePush: () -> Unit,
    onPickFile: () -> Unit,
    onSaveFile: (com.ts.messenger.net.FileRef) -> Unit,
    withMic: (() -> Unit) -> Unit,
) {
    if (state.call.phase != com.ts.messenger.call.CallPhase.Idle) {
        CallScreen(state.call, { withMic(vm::acceptCall) }, vm::declineCall, vm::hangupCall, vm::toggleMute, vm::toggleSpeaker)
        return
    }
    when (val s = state.screen) {
        Screen.Connect -> ConnectScreen(state, vm::connect, if (state.movingServer) vm::cancelMoveServer else null)
        is Screen.ConfirmPin -> ConfirmPinScreen(s.probe, state.busy, state.error, { vm.confirmPin(s.probe) }, if (state.movingServer) vm::cancelMoveServer else vm::cancelPin)
        Screen.Login -> LoginScreen(state, vm::login, { vm.goTo(Screen.Register) }, vm::changeServer)
        Screen.Register -> RegisterScreen(state, vm::register, { vm.goTo(Screen.Login) })
        is Screen.Recovery -> RecoveryScreen(s.code, vm::recoverySaved)
        Screen.Home -> ConversationListScreen(state, vm::openChat, vm::openNewChat, vm::signOut, vm::dismissNotice, onEnablePush, vm::disablePush, vm::toggleBackground, vm::startMoveServer)
        Screen.NewChat -> NewChatScreen(state, vm::search, vm::startChatWith, vm::leaveNewChat)
        Screen.Chat -> ChatScreen(state, vm::closeChat, vm::sendMessage, onPickFile, onSaveFile, vm::loadImage, vm::showSafety) { withMic(vm::startCall) }
    }
    state.identityAlert?.let { alert ->
        IdentityChangedDialog(alert, vm::acceptIdentity, vm::dismissIdentityAlert)
    }
    state.certChange?.let { change ->
        CertChangedDialog(change, vm::acceptCertChange, vm::rejectCertChange)
    }
    state.safety?.let { info ->
        AlertDialog(
            onDismissRequest = vm::dismissSafety,
            title = { Text(stringResource(R.string.safety_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (info.number != null) {
                        SelectionContainer {
                            Text(info.number, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyLarge)
                        }
                        Text(stringResource(R.string.safety_body), style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text(stringResource(R.string.safety_unavailable))
                    }
                }
            },
            confirmButton = { TextButton(onClick = vm::dismissSafety) { Text(stringResource(R.string.call_ok)) } },
        )
    }
    state.call.notice?.let { res ->
        AlertDialog(
            onDismissRequest = vm::dismissCallNotice,
            text = { Text(stringResource(res)) },
            confirmButton = { TextButton(onClick = vm::dismissCallNotice) { Text(stringResource(R.string.call_ok)) } },
        )
    }
}

@Composable
fun CertChangedDialog(change: com.ts.messenger.CertChange, onAccept: () -> Unit, onReject: () -> Unit) {
    fun group(fp: String) = fp.chunked(8).joinToString(" ")
    AlertDialog(
        onDismissRequest = onReject,
        title = { Text(stringResource(R.string.cert_changed_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.cert_changed_body))
                Text(stringResource(R.string.cert_changed_old), style = MaterialTheme.typography.labelMedium)
                SelectionContainer {
                    Text(group(change.oldFingerprint), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
                Text(stringResource(R.string.cert_changed_new), style = MaterialTheme.typography.labelMedium)
                SelectionContainer {
                    Text(group(change.probe.leafFingerprint), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onAccept) { Text(stringResource(R.string.cert_changed_accept)) } },
        dismissButton = { TextButton(onClick = onReject) { Text(stringResource(R.string.cert_changed_reject)) } },
    )
}
