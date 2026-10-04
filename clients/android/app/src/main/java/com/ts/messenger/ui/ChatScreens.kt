package com.ts.messenger.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ts.messenger.PushStatus
import com.ts.messenger.R
import com.ts.messenger.UiState
import com.ts.messenger.crypto.PeerIdentityChangedException
import com.ts.messenger.net.ChatMessage
import com.ts.messenger.net.DmChannel
import com.ts.messenger.net.FileRef
import com.ts.messenger.net.UserPublic
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ConversationListScreen(
    state: UiState,
    onOpen: (DmChannel) -> Unit,
    onNew: () -> Unit,
    onSignOut: () -> Unit,
    onDismissNotice: () -> Unit,
    onEnablePush: () -> Unit,
    onDisablePush: () -> Unit,
    onToggleBackground: () -> Unit,
    onChangeAddress: () -> Unit,
    onSecurity: () -> Unit,
    onPickAvatar: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    Surface(modifier = Modifier.fillMaxSize(), color = colors.surface) {
        Box(modifier = Modifier.safeDrawingPadding().fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopBar {
                    state.user?.let { me ->
                        Box(modifier = Modifier.padding(start = 12.dp).clickable(onClick = onPickAvatar)) {
                            Avatar(me.displayName.ifBlank { me.username }, me.id, size = 38.dp)
                        }
                    }
                    Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(stringResource(R.string.home_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        state.user?.let {
                            Text(it.displayName, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Box {
                        IconBtn(IconKind.More, colors.onSurfaceVariant) { menuOpen = true }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            when (state.push) {
                                PushStatus.On -> DropdownMenuItem(
                                    text = { Text(stringResource(R.string.push_disable)) },
                                    onClick = { menuOpen = false; onDisablePush() },
                                )
                                PushStatus.Off -> DropdownMenuItem(
                                    text = { Text(stringResource(R.string.push_enable)) },
                                    onClick = { menuOpen = false; onEnablePush() },
                                )
                                PushStatus.NoDistributor -> if (!state.background) DropdownMenuItem(
                                    text = { Text(stringResource(R.string.push_no_distributor), style = MaterialTheme.typography.bodySmall) },
                                    enabled = false,
                                    onClick = {},
                                )
                                PushStatus.ServerUnsupported -> if (!state.background) DropdownMenuItem(
                                    text = { Text(stringResource(R.string.push_server_unsupported), style = MaterialTheme.typography.bodySmall) },
                                    enabled = false,
                                    onClick = {},
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(if (state.background) R.string.bg_disable else R.string.bg_enable)) },
                                onClick = { menuOpen = false; onToggleBackground() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.bg_battery)) },
                                onClick = {
                                    menuOpen = false
                                    runCatching {
                                        ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.avatar_change)) },
                                onClick = { menuOpen = false; onPickAvatar() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.security_menu)) },
                                onClick = { menuOpen = false; onSecurity() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.move_server)) },
                                onClick = { menuOpen = false; onChangeAddress() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.sign_out), color = colors.error) },
                                onClick = { menuOpen = false; onSignOut() },
                            )
                        }
                    }
                }
                if (state.connected) {
                    Row(
                        modifier = Modifier.fillMaxWidth().background(colors.primary.copy(alpha = 0.08f)).padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TsIcon(IconKind.Lock, colors.primary, 14.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("${stringResource(R.string.sec_home)} · ${stringResource(R.string.sec_e2e)}", style = MaterialTheme.typography.labelMedium, color = colors.primary)
                    }
                }
                if (!state.connected) {
                    Text(
                        stringResource(R.string.offline_banner),
                        color = colors.onPrimary,
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().background(colors.error).padding(vertical = 6.dp),
                    )
                }
                state.notice?.let { res ->
                    Row(
                        modifier = Modifier.fillMaxWidth().background(colors.primaryContainer).padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(res), style = MaterialTheme.typography.bodyMedium, color = colors.onPrimaryContainer, modifier = Modifier.weight(1f))
                        TextButton(onClick = onDismissNotice) { Text(stringResource(R.string.ok)) }
                    }
                }
                if (!state.background && (state.push == PushStatus.NoDistributor || state.push == PushStatus.ServerUnsupported)) {
                    Text(
                        stringResource(if (state.push == PushStatus.NoDistributor) R.string.push_no_distributor else R.string.push_server_unsupported),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().background(colors.surfaceVariant).padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                if (state.dms.isEmpty()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.no_chats),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(32.dp),
                        )
                    }
                } else {
                    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 88.dp)) {
                        items(state.dms.sortedByDescending { state.lastActivity[it.channel.id].orEmpty() }, key = { it.otherUser.id }) { dm ->
                            PersonRow(dm.otherUser, unread = dm.channel.id in state.unread) { onOpen(dm) }
                        }
                    }
                }
            }
            ExtendedFloatingActionButton(
                onClick = onNew,
                containerColor = colors.primary,
                contentColor = colors.onPrimary,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                content = {
                    TsIcon(IconKind.Plus, colors.onPrimary)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.new_chat), fontWeight = FontWeight.Medium)
                },
            )
        }
    }
}

/** One contact line: avatar, name, @username, hairline separator inset past the avatar. */
@Composable
private fun PersonRow(user: UserPublic, enabled: Boolean = true, unread: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(user.displayName.ifBlank { user.username }, user.id)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    user.displayName.ifBlank { user.username },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("@${user.username}", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (unread) {
                Spacer(Modifier.width(12.dp))
                Box(modifier = Modifier.size(12.dp).background(colors.primary, androidx.compose.foundation.shape.CircleShape))
            }
        }
        HorizontalDivider(modifier = Modifier.padding(start = 82.dp), color = colors.outlineVariant)
    }
}

@Composable
fun NewChatScreen(
    state: UiState,
    onSearch: (String) -> Unit,
    onPick: (UserPublic) -> Unit,
    onBack: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    Surface(modifier = Modifier.fillMaxSize(), color = colors.surface) {
        Column(modifier = Modifier.safeDrawingPadding().imePadding().fillMaxSize()) {
            TopBar {
                IconBtn(IconKind.Back, colors.onSurface, onClick = onBack)
                Text(stringResource(R.string.new_chat), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            Box(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)
                    .clip(RoundedCornerShape(24.dp)).background(colors.surfaceVariant)
                    .padding(horizontal = 18.dp, vertical = 14.dp),
            ) {
                if (query.isEmpty()) {
                    Text(stringResource(R.string.search_users), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it; onSearch(it) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                    cursorBrush = SolidColor(colors.primary),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            ErrorText(state.error)
            if (query.trim().length < 2) {
                Text(stringResource(R.string.search_min), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            } else if (state.searchResults.isEmpty()) {
                Text(stringResource(R.string.no_results), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(state.searchResults, key = { it.id }) { u ->
                    PersonRow(u, enabled = !state.busy) { onPick(u) }
                }
            }
        }
    }
}

@Composable
fun ChatScreen(
    state: UiState,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onPickFile: () -> Unit,
    onSaveFile: (FileRef) -> Unit,
    loadImage: suspend (FileRef) -> android.graphics.Bitmap?,
    onSafety: () -> Unit,
    onCall: () -> Unit,
) {
    val dm = state.current ?: return
    val colors = MaterialTheme.colorScheme
    // Deliberately not rememberSaveable: an unsent draft must not end up in saved instance state.
    var draft by remember { mutableStateOf("") }
    val other = dm.otherUser
    val title = other.displayName.ifBlank { other.username }
    Surface(modifier = Modifier.fillMaxSize(), color = colors.background) {
        Column(modifier = Modifier.safeDrawingPadding().imePadding()) {
            TopBar {
                IconBtn(IconKind.Back, colors.onSurface, onClick = onBack)
                Avatar(title, other.id, size = 40.dp)
                Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val (trustColor, trustText) = when (state.trust) {
                            com.ts.messenger.Trust.Verified -> Color(0xFF2DD4A7) to R.string.trust_verified
                            com.ts.messenger.Trust.Changed -> Color(0xFFEF5B5B) to R.string.trust_changed
                            com.ts.messenger.Trust.Unverified -> Color(0xFFF5B84A) to R.string.trust_unverified
                        }
                        TsIcon(if (state.trust == com.ts.messenger.Trust.Verified) IconKind.ShieldLock else IconKind.Lock, trustColor, 13.dp)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(trustText), style = MaterialTheme.typography.bodySmall, color = trustColor, maxLines = 1)
                    }
                    if (state.showPresence && state.onlinePeers.contains(other.id)) {
                        Text(stringResource(R.string.presence_online), style = MaterialTheme.typography.bodySmall, color = colors.primary, maxLines = 1)
                    }
                }
                IconBtn(IconKind.Shield, colors.onSurfaceVariant, onClick = onSafety)
                IconBtn(IconKind.Phone, colors.primary, enabled = state.connected, onClick = onCall)
            }
            // Thin line under the header: green = keys verified, amber = not yet, red = changed.
            androidx.compose.foundation.layout.Box(
                Modifier.fillMaxWidth().height(2.dp).background(
                    when (state.trust) {
                        com.ts.messenger.Trust.Verified -> Color(0xFF2DD4A7)
                        com.ts.messenger.Trust.Changed -> Color(0xFFEF5B5B)
                        com.ts.messenger.Trust.Unverified -> Color(0xFFF5B84A)
                    },
                ),
            )
            if (!state.connected) {
                Text(
                    stringResource(R.string.offline_banner),
                    color = colors.onPrimary,
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().background(colors.error).padding(vertical = 4.dp),
                )
            }
            if (state.messages.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                        SecureNotice()
                        Text(
                            stringResource(R.string.empty_chat),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                }
            } else {
                val reversed = remember(state.messages) { state.messages.asReversed() }
                val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                // Keep the newest message in view: when a message arrives (and you are at the bottom
                // or it is yours) and when the keyboard opens, jump to the latest one.
                val newestId = reversed.firstOrNull()?.id
                val newestMine = reversed.firstOrNull()?.let { it.senderId != other.id } ?: false
                androidx.compose.runtime.LaunchedEffect(newestId) {
                    if (newestId != null && (newestMine || listState.firstVisibleItemIndex <= 2)) {
                        listState.animateScrollToItem(0)
                    }
                }
                val imeOpen = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
                androidx.compose.runtime.LaunchedEffect(imeOpen) {
                    if (imeOpen) listState.scrollToItem(0)
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    reverseLayout = true,
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    itemsIndexed(reversed, key = { _, m -> m.id }) { i, m ->
                        val older = reversed.getOrNull(i + 1)
                        Column {
                            if (older == null) SecureNotice()
                            if (older == null || localDate(older.createdAt) != localDate(m.createdAt)) {
                                DateChip(m.createdAt)
                            }
                            Bubble(m, mine = m.senderId != other.id, onSaveFile, loadImage)
                        }
                    }
                }
            }
            ErrorText(state.error)
            if (state.uploading) {
                Text(
                    stringResource(R.string.uploading),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                IconBtn(IconKind.Plus, colors.onSurfaceVariant, enabled = !state.uploading && state.connected, onClick = onPickFile)
                Box(
                    modifier = Modifier.weight(1f).padding(vertical = 2.dp)
                        .clip(RoundedCornerShape(22.dp)).background(colors.background)
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                ) {
                    if (draft.isEmpty()) {
                        Text(stringResource(R.string.message_hint), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                    }
                    BasicTextField(
                        value = draft,
                        onValueChange = { if (it.length <= 3000) draft = it },
                        maxLines = 5,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                        cursorBrush = SolidColor(colors.primary),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            autoCorrectEnabled = false, // keep messages out of keyboard suggestions
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.width(6.dp))
                val canSend = draft.isNotBlank()
                Box(
                    modifier = Modifier.padding(bottom = 2.dp).size(46.dp).clip(CircleShape)
                        .background(if (canSend) colors.primary else colors.outlineVariant)
                        .clickable(enabled = canSend) { onSend(draft); draft = "" },
                    contentAlignment = Alignment.Center,
                ) {
                    TsIcon(IconKind.Send, if (canSend) colors.onPrimary else colors.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SecureNotice() {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(14.dp)).background(colors.surface.copy(alpha = 0.9f)).padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        TsIcon(IconKind.ShieldLock, colors.primary, 22.dp)
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.sec_chat_notice), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun DateChip(iso: String) {
    val colors = MaterialTheme.colorScheme
    val date = localDate(iso) ?: return
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(
            date.format(DateTimeFormatter.ofPattern("d MMMM")),
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(colors.surface.copy(alpha = 0.85f)).padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun Bubble(
    m: ChatMessage,
    mine: Boolean,
    onSaveFile: (FileRef) -> Unit,
    loadImage: suspend (FileRef) -> android.graphics.Bitmap?,
) {
    val colors = MaterialTheme.colorScheme
    val bubbleColor = if (mine) colors.primaryContainer else Color(0xFF2A3540)
    val textColor = if (mine) colors.onPrimaryContainer else colors.onSurface
    val shape = RoundedCornerShape(
        topStart = 16.dp, topEnd = 16.dp,
        bottomStart = if (mine) 16.dp else 4.dp,
        bottomEnd = if (mine) 4.dp else 16.dp,
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = shape, color = bubbleColor, contentColor = textColor,
            border = if (mine) null else androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF45535F)),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 7.dp, bottom = 5.dp)) {
                val file = m.file
                if (m.ok && file != null) {
                    FileBody(file, onSaveFile, loadImage)
                } else if (m.ok) {
                    Text(m.text, style = MaterialTheme.typography.bodyLarge)
                } else {
                    Text(
                        stringResource(if (mine) R.string.own_other_device else R.string.undecryptable),
                        style = MaterialTheme.typography.bodyMedium,
                        fontStyle = FontStyle.Italic,
                    )
                }
                Text(
                    formatTime(m.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.End).padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun FileBody(
    file: FileRef,
    onSaveFile: (FileRef) -> Unit,
    loadImage: suspend (FileRef) -> android.graphics.Bitmap?,
) {
    val colors = MaterialTheme.colorScheme
    if (file.key.isEmpty()) {
        // A plaintext upload from the web client: deliberately not opened.
        Text(file.name, style = MaterialTheme.typography.bodyLarge)
        Text(
            stringResource(R.string.file_unsupported),
            style = MaterialTheme.typography.bodySmall,
            fontStyle = FontStyle.Italic,
        )
        return
    }
    if (file.mime.startsWith("image/") && file.mime != "image/svg+xml") {
        val bitmap by produceState<ImageBitmap?>(null, file.id) { value = loadImage(file)?.asImageBitmap() }
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = file.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp).clip(RoundedCornerShape(10.dp)).padding(bottom = 4.dp),
            )
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onSaveFile(file) }.padding(vertical = 2.dp)) {
        Box(
            modifier = Modifier.size(44.dp).clip(CircleShape).background(colors.primary),
            contentAlignment = Alignment.Center,
        ) { TsIcon(IconKind.Download, colors.onPrimary) }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(file.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(formatSize(file.size), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
}

private fun localDate(iso: String): LocalDate? = runCatching {
    OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).toLocalDate()
}.getOrNull()

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
