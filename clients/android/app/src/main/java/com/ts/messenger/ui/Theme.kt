package com.ts.messenger.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Accent = Color(0xFFE07A4F)

private val Dark = darkColorScheme(
    primary = Accent,
    background = Color(0xFF1A1816),
    surface = Color(0xFF221F1C),
    onBackground = Color(0xFFEDE8E2),
    onSurface = Color(0xFFEDE8E2),
    error = Color(0xFFE5534B),
)

private val Light = lightColorScheme(
    primary = Color(0xFFB5512C),
    background = Color(0xFFF8F5F0),
    surface = Color(0xFFFFFFFF),
    onBackground = Color(0xFF2C2520),
    onSurface = Color(0xFF2C2520),
    error = Color(0xFFD1242F),
)

@Composable
fun TsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
