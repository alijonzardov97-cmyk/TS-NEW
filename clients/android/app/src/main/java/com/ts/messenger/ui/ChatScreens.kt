package com.ts.messenger.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.ts.messenger.R
import com.ts.messenger.UiState
import com.ts.messenger.crypto.PeerIdentityChangedException
import com.ts.messenger.net.ChatMessage
import com.ts.messenger.net.UserPublic
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ConversationListScreen(
    state: UiState,
    onOpen: (com.ts.messenger.net.DmChannel) -> Unit,
    onNew: () -> Unit,
    onSignOut: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    Page(stringResource(R.string.home_title)) {
        state.user?.let { Text(it.displayName, style = MaterialTheme.typography.titleMedium) }
        if (!state.connected) {
            Text(stringResource(R.string.offline_banner), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        state.notice?.let { res ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(res), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onDismissNotice) { Text(stringResource(R.string.ok)) }
                }
            }
        }
        PrimaryButton(stringResource(R.string.new_chat), busy = false, onClick = onNew)
        if (state.dms.isEmpty()) {
            Text(stringResource(R.string.no_chats), style = MaterialTheme.typography.bodyMedium)
        }
        state.dms.forEach { dm ->
            Card(modifier = Modifier.fillMaxWidth().clickable { onOpen(dm) }) {
                Column(modifier = Modifier.padding(16.dp).heightIn(min = 40.dp)) {
                    Text(dm.otherUser.displayName, style = MaterialTheme.typography.titleMedium)
                    Text("@${dm.otherUser.username}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        LinkButton(stringResource(R.string.sign_out), onSignOut)
    }
}

@Composable
fun NewChatScreen(
    state: UiState,
    onSearch: (String) -> Unit,
    onPick: (UserPublic) -> Unit,
    onBack: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    Page(stringResource(R.string.new_chat)) {
        PlainField(query, { query = it; onSearch(it) }, stringResource(R.string.search_users))
        ErrorText(state.error)
        if (query.trim().length < 2) {
            Text(stringResource(R.string.search_min), style = MaterialTheme.typography.bodySmall)
        } else if (state.searchResults.isEmpty()) {
            Text(stringResource(R.string.no_results), style = MaterialTheme.typography.bodyMedium)
        }
        state.searchResults.forEach { u ->
            Card(modifier = Modifier.fillMaxWidth().clickable(enabled = !state.busy) { onPick(u) }) {
                Column(modifier = Modifier.padding(16.dp).heightIn(min = 40.dp)) {
                    Text(u.displayName, style = MaterialTheme.typography.titleMedium)
                    Text("@${u.username}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        LinkButton(stringResource(R.string.back), onBack)
    }
}

@Composable
fun ChatScreen(state: UiState, onBack: () -> Unit, onSend: (String) -> Unit) {
    val dm = state.current ?: return
    // Deliberately not rememberSaveable: an unsent draft must not end up in saved instance state.
    var draft by remember { mutableStateOf("") }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.safeDrawingPadding().imePadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
                Text(dm.otherUser.displayName, style = MaterialTheme.typography.titleMedium)
            }
            if (!state.connected) {
                Text(
                    stringResource(R.string.offline_banner),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            if (state.messages.isEmpty()) {
                Text(
                    stringResource(R.string.empty_chat),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f).padding(16.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    reverseLayout = true,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(state.messages.asReversed(), key = { it.id }) { m ->
                        Bubble(m, mine = m.senderId != dm.otherUser.id)
                    }
                }
            }
            ErrorText(state.error)
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { if (it.length <= 3000) draft = it },
                    label = { Text(stringResource(R.string.message_hint)) },
                    modifier = Modifier.weight(1f),
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        autoCorrectEnabled = false, // keep messages out of keyboard suggestions
                    ),
                )
                Button(
                    onClick = { onSend(draft); draft = "" },
                    enabled = draft.isNotBlank(),
                    modifier = Modifier.heightIn(min = 52.dp),
                ) { Text(stringResource(R.string.send)) }
            }
        }
    }
}

@Composable
private fun Bubble(m: ChatMessage, mine: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (m.ok) {
                    Text(m.text, style = MaterialTheme.typography.bodyLarge)
                } else {
                    Text(
                        stringResource(if (mine) R.string.own_other_device else R.string.undecryptable),
                        style = MaterialTheme.typography.bodyMedium,
                        fontStyle = FontStyle.Italic,
                    )
                }
                Spacer(Modifier.padding(top = 2.dp))
                Text(formatTime(m.createdAt), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun formatTime(iso: String): String = runCatching {
    OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("HH:mm"))
}.getOrDefault("")

@Composable
fun IdentityChangedDialog(alert: PeerIdentityChangedException, onAccept: () -> Unit, onReject: () -> Unit) {
    AlertDialog(
        onDismissRequest = onReject,
        title = { Text(stringResource(R.string.identity_changed_title)) },
        text = { Text(stringResource(R.string.identity_changed_body)) },
        confirmButton = { TextButton(onClick = onAccept) { Text(stringResource(R.string.identity_changed_accept)) } },
        dismissButton = { TextButton(onClick = onReject) { Text(stringResource(R.string.identity_changed_reject)) } },
    )
}
