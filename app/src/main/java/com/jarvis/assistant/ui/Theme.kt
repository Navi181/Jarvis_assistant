package com.jarvis.assistant.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Bg = Color(0xFF050B14)
val BgDeep = Color(0xFF08142A)
val Panel = Color(0xFF0D1828)
val Outline = Color(0xFF1E2D44)
val Ink = Color(0xFFE6F1FF)
val InkDim = Color(0xFF8CA3BF)
val Cyan = Color(0xFF22D3EE)
val Blue = Color(0xFF3B82F6)
val Violet = Color(0xFF8B5CF6)
val Good = Color(0xFF34D399)
val Warn = Color(0xFFFBBF24)

private val scheme = darkColorScheme(
    primary = Cyan,
    onPrimary = Color(0xFF00252B),
    secondary = Violet,
    background = Bg,
    onBackground = Ink,
    surface = Panel,
    onSurface = Ink,
    surfaceVariant = Color(0xFF111C2E),
    onSurfaceVariant = InkDim,
    outline = Outline
)

@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
