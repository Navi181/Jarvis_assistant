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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/** Frosted-glass surface used by every card. */
fun Modifier.glass(shape: Shape = RoundedCornerShape(20.dp)): Modifier = this
    .clip(shape)
    .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.03f))))
    .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.04f))), shape)

private class Star(val x: Float, val y: Float, val r: Float, val phase: Float, val twinkle: Int)

/** [revs] = full orbits per 4-minute loop, so the animation repeats seamlessly. */
private class Planet(val sizeDp: Float, val color: Color, val revs: Int, val start: Float, val ring: Boolean = false)

private val PLANETS = listOf(
    Planet(2.2f, Color(0xFF9CA3AF), 12, 0.10f),                // Mercury
    Planet(3.4f, Color(0xFFE8C28A), 8, 0.55f),                 // Venus
    Planet(3.7f, Color(0xFF3B82F6), 5, 0.80f),                 // Earth
    Planet(2.8f, Color(0xFFD9693F), 3, 0.30f),                 // Mars
    Planet(7.0f, Color(0xFFD6A77A), 2, 0.65f),                 // Jupiter
    Planet(6.0f, Color(0xFFE3CB8F), 1, 0.05f, ring = true),    // Saturn
    Planet(4.6f, Color(0xFF7DD3E8), 1, 0.45f),                 // Uranus
    Planet(4.4f, Color(0xFF4F6EF0), 1, 0.92f)                  // Neptune
)

/** A dark, calm night sky with a tilted, slowly turning solar system. Loops seamlessly. */
@Composable
fun Backdrop(modifier: Modifier = Modifier) {
    val stars = remember {
        List(150) { Star(Random.nextFloat(), Random.nextFloat(), Random.nextFloat() * 1.4f + 0.4f, Random.nextFloat() * 6.28f, 20 + Random.nextInt(40)) }
    }
    val belt = remember { List(90) { Pair(Random.nextFloat(), Random.nextFloat() * 2f - 1f) } }
    val inf = rememberInfiniteTransition(label = "backdrop")
    val t by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(240000, easing = LinearEasing)), label = "t")
    val tau = (2 * PI).toFloat()

    Canvas(modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(Bg, BgDeep, Bg)))
        val w = size.width
        val h = size.height
        val cx = w * 0.5f
        val cy = h * 0.40f
        val c = Offset(cx, cy)

        for (s in stars) {
            val a = 0.2f + 0.6f * abs(sin(t * tau * s.twinkle + s.phase))
            drawCircle(Color.White.copy(alpha = a * 0.7f), s.r.dp.toPx() * 0.5f, Offset(s.x * w, s.y * h))
        }

        // the sun, warm and dim so it never fights the orb or the text
        val sunR = w * 0.24f
        drawCircle(
            Brush.radialGradient(listOf(Color(0xFFFFB454).copy(alpha = 0.30f), Color(0xFFFF7A1A).copy(alpha = 0.08f), Color.Transparent), c, sunR),
            radius = sunR, center = c
        )

        rotate(-14f, c) {
            PLANETS.forEachIndexed { i, p ->
                val rx = w * (0.15f + 0.068f * i)
                val ry = rx * 0.36f
                drawOval(Color.White.copy(alpha = 0.07f), Offset(cx - rx, cy - ry), Size(rx * 2, ry * 2), style = Stroke(1.dp.toPx()))
                if (i == 3) {
                    // asteroid belt between Mars and Jupiter
                    val brx = w * (0.15f + 0.068f * 3.5f)
                    for (d in belt) {
                        val ang = d.first * tau + t * tau
                        val rr = brx + d.second * w * 0.012f
                        drawCircle(Color(0xFFB8A890).copy(alpha = 0.45f), 0.7.dp.toPx(), Offset(cx + rr * cos(ang), cy + rr * 0.36f * sin(ang)))
                    }
                }
                val ang = p.start * tau + t * tau * p.revs
                val px = cx + rx * cos(ang)
                val py = cy + ry * sin(ang)
                val r = p.sizeDp.dp.toPx()
                val shade = if (sin(ang) < 0) 0.75f else 1f
                val lit = Offset(px + (cx - px) * 0.12f, py + (cy - py) * 0.12f)
                drawCircle(
                    Brush.radialGradient(listOf(lerp(p.color, Color.White, 0.35f), p.color, lerp(p.color, Color.Black, 0.7f)), lit, r * 1.6f),
                    radius = r, center = Offset(px, py), alpha = shade
                )
                if (p.ring) {
                    drawOval(p.color.copy(alpha = 0.55f * shade), Offset(px - r * 1.9f, py - r * 0.55f), Size(r * 3.8f, r * 1.1f), style = Stroke(1.2.dp.toPx()))
                }
            }
        }

        // vignette: darker edges, so everything reads as deep space
        drawRect(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)), c, max(w, h) * 0.8f))
    }
}
