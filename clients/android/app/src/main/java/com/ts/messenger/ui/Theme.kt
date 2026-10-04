package com.ts.messenger.ui

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// "Safe" palette: graphite surfaces, one teal accent. The app is always dark.
// background = chat wallpaper, surface = bars and lists,
// primaryContainer = outgoing bubble, surface = incoming bubble.
private val Dark = darkColorScheme(
    primary = Color(0xFF2DD4A7),
    onPrimary = Color(0xFF06231B),
    primaryContainer = Color(0xFF12382F),
    onPrimaryContainer = Color(0xFFE6EDF3),
    background = Color(0xFF0E1114),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF12161A),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF1A2026),
    onSurfaceVariant = Color(0xFF8794A2),
    outline = Color(0xFF55626E),
    outlineVariant = Color(0xFF1F262D),
    error = Color(0xFFEF5B5B),
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
    val dark = true
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
