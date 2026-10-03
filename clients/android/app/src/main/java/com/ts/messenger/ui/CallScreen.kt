package com.ts.messenger.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ts.messenger.R
import com.ts.messenger.call.CallPhase
import com.ts.messenger.call.CallUi

@Composable
fun CallScreen(
    call: CallUi,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onHangup: () -> Unit,
    onMute: () -> Unit,
    onSpeaker: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.safeDrawingPadding().padding(24.dp).fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(Modifier.height(48.dp))
                Text(call.peerName, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                Text(
                    stringResource(
                        when (call.phase) {
                            CallPhase.Ringing -> R.string.call_incoming
                            CallPhase.Calling -> R.string.call_calling
                            CallPhase.Connecting -> R.string.call_connecting
                            else -> R.string.call_active
                        },
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            if (call.sas != null && call.phase != CallPhase.Ringing) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.call_code_title), style = MaterialTheme.typography.labelLarge)
                    SelectionContainer {
                        Text(call.sas, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.headlineSmall)
                    }
                    Text(
                        stringResource(R.string.call_code_hint),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            if (call.phase == CallPhase.Ringing) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedButton(onClick = onDecline, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                        Text(stringResource(R.string.call_decline))
                    }
                    Button(onClick = onAccept, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                        Text(stringResource(R.string.call_accept))
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = onMute, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                            Text(stringResource(if (call.muted) R.string.call_unmute else R.string.call_mute))
                        }
                        OutlinedButton(onClick = onSpeaker, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                            Text(stringResource(if (call.speaker) R.string.call_earpiece else R.string.call_speaker))
                        }
                    }
                    Button(
                        onClick = onHangup,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    ) { Text(stringResource(R.string.call_hangup)) }
                }
            }
        }
    }
}
