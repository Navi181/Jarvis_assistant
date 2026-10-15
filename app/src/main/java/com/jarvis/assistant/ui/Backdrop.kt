package com.jarvis.assistant.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/** Frosted-glass surface used by every card. */
fun Modifier.glass(shape: Shape = RoundedCornerShape(20.dp)): Modifier = this
    .clip(shape)
    .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.03f))))
    .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.04f))), shape)

private class Star(val x: Float, val y: Float, val r: Float, val phase: Float, val speed: Int)

/** Slowly drifting starfield with two breathing aurora glows. Loops seamlessly. */
@Composable
fun Backdrop(modifier: Modifier = Modifier) {
    val stars = remember {
        List(80) { Star(Random.nextFloat(), Random.nextFloat(), Random.nextFloat() * 1.5f + 0.5f, Random.nextFloat() * 6.28f, 1 + Random.nextInt(3)) }
    }
    val inf = rememberInfiniteTransition(label = "backdrop")
    val t by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(60000, easing = LinearEasing)), label = "t")
    val tau = (2 * PI).toFloat()

    Canvas(modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(Bg, BgDeep, Bg)))
        val w = size.width
        val h = size.height
        val c1 = Offset(w * (0.2f + 0.12f * sin(t * tau)), h * 0.22f)
        val c2 = Offset(w * (0.85f - 0.1f * sin(t * tau)), h * 0.8f)
        drawCircle(Brush.radialGradient(listOf(Cyan.copy(alpha = 0.16f), Color.Transparent), c1, w * 0.85f), radius = w * 0.85f, center = c1)
        drawCircle(Brush.radialGradient(listOf(Violet.copy(alpha = 0.15f), Color.Transparent), c2, w * 0.9f), radius = w * 0.9f, center = c2)
        for (s in stars) {
            val y = (((s.y - t * s.speed) % 1f) + 1f) % 1f
            val a = 0.25f + 0.55f * abs(sin(t * tau * 30f + s.phase))
            drawCircle(Color.White.copy(alpha = a * 0.8f), s.r.dp.toPx() * 0.6f, Offset(s.x * w, y * h))
        }
    }
}
