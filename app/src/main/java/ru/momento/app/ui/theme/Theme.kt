package ru.momento.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF287FE8), onPrimary = Color.White,
    primaryContainer = Color(0xFFEAF3FF), onPrimaryContainer = Color(0xFF172B4D),
    secondary = Color(0xFF58708F), background = Color(0xFFF5F7FA),
    surface = Color.White, onSurface = Color(0xFF172B4D),
    onSurfaceVariant = Color(0xFF65758B), outline = Color(0xFFDCE4EF)
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF9FC6FF), onPrimary = Color(0xFF00325B),
    primaryContainer = Color(0xFF164A80), onPrimaryContainer = Color(0xFFD5E6FF),
    secondary = Color(0xFFB6C8E4), background = Color(0xFF0D1422),
    surface = Color(0xFF172237), onSurface = Color(0xFFE8EFFB),
    onSurfaceVariant = Color(0xFFB8C5D9), outline = Color(0xFF52647E)
)

@Composable
fun MomentoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}
