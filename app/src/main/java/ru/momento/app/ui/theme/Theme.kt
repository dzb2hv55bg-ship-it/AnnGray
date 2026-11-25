package ru.momento.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF005BC4), onPrimary = Color.White,
    primaryContainer = Color(0xFFEAF3FF), onPrimaryContainer = Color(0xFF172B4D),
    secondary = Color(0xFF58708F), background = Color(0xFFF1F5FE),
    surface = Color.White, surfaceContainerLow = Color(0xFFFAFCFF),
    surfaceContainer = Color(0xFFE9F0FC), surfaceContainerHigh = Color(0xFFDEE8F8),
    outlineVariant = Color(0xFFD5E1F4), onSurface = Color(0xFF172B4D),
    onSurfaceVariant = Color(0xFF65758B), outline = Color(0xFFDCE4EF)
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF9FC6FF), onPrimary = Color(0xFF00325B),
    primaryContainer = Color(0xFF164A80), onPrimaryContainer = Color(0xFFD5E6FF),
    secondary = Color(0xFFB6C8E4), background = Color(0xFF0B1425),
    surface = Color(0xFF14223A), surfaceContainerLow = Color(0xFF152640),
    surfaceContainer = Color(0xFF1B2D49), surfaceContainerHigh = Color(0xFF253A59),
    outlineVariant = Color(0xFF314764), onSurface = Color(0xFFE8EFFB),
    onSurfaceVariant = Color(0xFFB8C5D9), outline = Color(0xFF52647E)
)

private val MomentoShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp), small = RoundedCornerShape(18.dp),
    medium = RoundedCornerShape(24.dp), large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun MomentoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, shapes = MomentoShapes, content = content)
}
