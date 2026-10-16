package com.jarvis.assistant.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

val Bg = Color(0xFF02050C)
val BgDeep = Color(0xFF050B18)
val Panel = Color(0xFF0A1220)
val Outline = Color(0xFF1A2639)
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
    surfaceVariant = Color(0xFF0E1726),
    onSurfaceVariant = InkDim,
    outline = Outline
)

@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme) {
        // Roomier text everywhere: lines sit further apart and letters get a touch of air.
        CompositionLocalProvider(
            LocalTextStyle provides LocalTextStyle.current.copy(lineHeight = 1.45.em, letterSpacing = 0.15.sp)
        ) { content() }
    }
}
