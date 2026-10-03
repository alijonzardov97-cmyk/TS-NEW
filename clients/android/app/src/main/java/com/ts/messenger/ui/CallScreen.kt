package com.ts.messenger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ts.messenger.R
import com.ts.messenger.call.CallPhase
import com.ts.messenger.call.CallUi

private val CallBgTop = Color(0xFF1F3550)
private val CallBgBottom = Color(0xFF0B121A)
private val Green = Color(0xFF34C759)
private val Red = Color(0xFFEB4D4B)

@Composable
fun CallScreen(
    call: CallUi,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onHangup: () -> Unit,
    onMute: () -> Unit,
    onSpeaker: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(CallBgTop, CallBgBottom))),
    ) {
        Column(
            modifier = Modifier.safeDrawingPadding().padding(24.dp).fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Spacer(Modifier.height(40.dp))
                Avatar(call.peerName, call.peerName, size = 120.dp)
                Spacer(Modifier.height(6.dp))
                Text(
                    call.peerName,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
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
                    color = Color.White.copy(alpha = 0.7f),
                )
            }

            if (call.sas != null && call.phase != CallPhase.Ringing) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                        .background(Color.White.copy(alpha = 0.08f)).padding(16.dp),
                ) {
                    Text(stringResource(R.string.call_code_title), style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.7f))
                    SelectionContainer {
                        Text(call.sas, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleLarge, color = Color.White, textAlign = TextAlign.Center)
                    }
                    Text(
                        stringResource(R.string.call_code_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                    )
                }
            }

            if (call.phase == CallPhase.Ringing) {
                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    RoundAction(IconKind.Phone, stringResource(R.string.call_decline), Red, Color.White, onDecline, rotation = 135f)
                    RoundAction(IconKind.Phone, stringResource(R.string.call_accept), Green, Color.White, onAccept)
                }
            } else {
                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    RoundAction(
                        if (call.muted) IconKind.MicOff else IconKind.Mic,
                        stringResource(if (call.muted) R.string.call_unmute else R.string.call_mute),
                        if (call.muted) Color.White else Color.White.copy(alpha = 0.16f),
                        if (call.muted) CallBgBottom else Color.White,
                        onMute,
                    )
                    RoundAction(IconKind.Phone, stringResource(R.string.call_hangup), Red, Color.White, onHangup, rotation = 135f)
                    RoundAction(
                        IconKind.Speaker,
                        stringResource(if (call.speaker) R.string.call_earpiece else R.string.call_speaker),
                        if (call.speaker) Color.White else Color.White.copy(alpha = 0.16f),
                        if (call.speaker) CallBgBottom else Color.White,
                        onSpeaker,
                    )
                }
            }
        }
    }
}

@Composable
private fun RoundAction(
    kind: IconKind,
    label: String,
    background: Color,
    foreground: Color,
    onClick: () -> Unit,
    rotation: Float = 0f,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier.size(68.dp).clip(CircleShape).background(background).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            TsIcon(kind, foreground, 30.dp, Modifier.rotate(rotation))
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.85f))
    }
}
