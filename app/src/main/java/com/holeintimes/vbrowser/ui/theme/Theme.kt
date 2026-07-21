package com.holeintimes.vbrowser.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Ink = Color(0xFF1A3A4A)
private val Sand = Color(0xFFF5F2EC)
private val Accent = Color(0xFFC45C26)
private val Mist = Color(0xFFE2E8E4)
private val Deep = Color(0xFF0F2430)

private val LightColors = lightColorScheme(
    primary = Ink,
    onPrimary = Color.White,
    secondary = Accent,
    onSecondary = Color.White,
    background = Sand,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Mist,
    onSurfaceVariant = Color(0xFF3D4F5A),
    outline = Color(0xFF8A9AA3)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8EB8C8),
    onPrimary = Deep,
    secondary = Color(0xFFE08A5A),
    onSecondary = Deep,
    background = Deep,
    onBackground = Sand,
    surface = Color(0xFF163040),
    onSurface = Sand,
    surfaceVariant = Color(0xFF1F3D4D),
    onSurfaceVariant = Mist,
    outline = Color(0xFF6A7F8A)
)

@Composable
fun VBrowserTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
