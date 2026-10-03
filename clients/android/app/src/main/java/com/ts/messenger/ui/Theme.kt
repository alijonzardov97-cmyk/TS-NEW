package com.ts.messenger.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Calm, strict messenger palette: one blue accent, neutral surfaces.
// background = chat wallpaper, surface = bars and lists,
// primaryContainer = outgoing bubble, surface = incoming bubble.
private val Dark = darkColorScheme(
    primary = Color(0xFF2F86E0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2B5278),
    onPrimaryContainer = Color(0xFFF2F6FA),
    background = Color(0xFF0E1621),
    onBackground = Color(0xFFF2F6FA),
    surface = Color(0xFF17212B),
    onSurface = Color(0xFFF2F6FA),
    surfaceVariant = Color(0xFF1F2B38),
    onSurfaceVariant = Color(0xFF8B9BA8),
    outline = Color(0xFF55626E),
    outlineVariant = Color(0xFF223040),
    error = Color(0xFFEC5B5B),
)

private val Light = lightColorScheme(
    primary = Color(0xFF3390EC),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9EBFF),
    onPrimaryContainer = Color(0xFF10202E),
    background = Color(0xFFE6EBEF),
    onBackground = Color(0xFF10202E),
    surface = Color.White,
    onSurface = Color(0xFF10202E),
    surfaceVariant = Color(0xFFF1F3F5),
    onSurfaceVariant = Color(0xFF707579),
    outline = Color(0xFF9AA2A8),
    outlineVariant = Color(0xFFE0E4E8),
    error = Color(0xFFD93636),
)

@Composable
fun TsTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) Dark else Light
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as? Activity ?: return@SideEffect
            val window = activity.window
            window.statusBarColor = scheme.surface.toArgb()
            window.navigationBarColor = scheme.surface.toArgb()
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
