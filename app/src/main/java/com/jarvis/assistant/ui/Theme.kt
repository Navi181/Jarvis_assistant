package com.jarvis.assistant.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.material3.Typography
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

val Bg = Color(0xFF000206)
val BgDeep = Color(0xFF010510)
val Panel = Color(0xFF060B15)
val Outline = Color(0xFF111A2A)
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
    surfaceVariant = Color(0xFF080E19),
    onSurfaceVariant = InkDim,
    outline = Outline
)

@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val type = remember {
        Fonts.load(ctx)
        val d = Typography()
        val t = Fonts.title
        val b = Fonts.body
        val m = Fonts.mono
        Typography(
            displayLarge = d.displayLarge.copy(fontFamily = t, fontWeight = FontWeight.Bold),
            displayMedium = d.displayMedium.copy(fontFamily = t, fontWeight = FontWeight.Bold),
            displaySmall = d.displaySmall.copy(fontFamily = t, fontWeight = FontWeight.Bold),
            headlineLarge = d.headlineLarge.copy(fontFamily = t, fontWeight = FontWeight.Bold),
            headlineMedium = d.headlineMedium.copy(fontFamily = t, fontWeight = FontWeight.Bold),
            headlineSmall = d.headlineSmall.copy(fontFamily = t, fontWeight = FontWeight.Bold),
            titleLarge = d.titleLarge.copy(fontFamily = t, fontWeight = FontWeight.SemiBold),
            titleMedium = d.titleMedium.copy(fontFamily = t, fontWeight = FontWeight.SemiBold),
            titleSmall = d.titleSmall.copy(fontFamily = b, fontWeight = FontWeight.SemiBold),
            bodyLarge = d.bodyLarge.copy(fontFamily = b),
            bodyMedium = d.bodyMedium.copy(fontFamily = b),
            bodySmall = d.bodySmall.copy(fontFamily = b),
            labelLarge = d.labelLarge.copy(fontFamily = b, fontWeight = FontWeight.Medium),
            labelMedium = d.labelMedium.copy(fontFamily = m),
            labelSmall = d.labelSmall.copy(fontFamily = m)
        )
    }
    MaterialTheme(colorScheme = scheme, typography = type) {
        // Roomier text everywhere: lines sit further apart and letters get a touch of air.
        CompositionLocalProvider(
            LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = Fonts.body, lineHeight = 1.45.em, letterSpacing = 0.15.sp)
        ) { content() }
    }
}
