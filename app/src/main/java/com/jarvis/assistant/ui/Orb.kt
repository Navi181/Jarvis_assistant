package com.jarvis.assistant.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.core.Phase
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A glowing plasma sphere inside a holographic HUD: swirling energy under glass, a bright rim and a highlight,
 * a radar sweep, ticks and orbiting particles. Idle: calm. Listening: bars follow your voice. Thinking: violet,
 * fast sweep. Speaking: pulsing bars.
 */
@Composable
fun JarvisOrb(phase: Phase, level: Float, modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "orb")
    // one clock that loops every 24 s; everything else is an integer multiple of it so loops are seamless
    val t by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(24000, easing = LinearEasing)), label = "t")
    val breathe by inf.animateFloat(
        0f, 1f, infiniteRepeatable(tween(2800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe"
    )
    val smooth by animateFloatAsState(level, spring(stiffness = Spring.StiffnessLow), label = "level")
    val core by animateColorAsState(
        when (phase) {
            Phase.IDLE -> Cyan
            Phase.LISTENING -> Color(0xFFFF9F1C)
            Phase.THINKING -> Violet
            Phase.SPEAKING -> Blue
        },
        tween(700), label = "core"
    )
    val speed = when (phase) {
        Phase.IDLE -> 1f
        Phase.LISTENING -> 2f
        Phase.THINKING -> 4f
        Phase.SPEAKING -> 2f
    }
    val tau = 2f * PI.toFloat()

    Canvas(modifier.aspectRatio(1f)) {
        val c = center
        val maxR = size.minDimension / 2f
        val base = maxR * 0.50f
        val pulse = when (phase) {
            Phase.IDLE -> 0.02f + 0.03f * breathe
            Phase.LISTENING -> 0.03f + smooth * 0.14f
            Phase.THINKING -> 0.03f + 0.04f * breathe
            Phase.SPEAKING -> 0.05f + 0.05f * (0.5f + 0.5f * sin(t * tau * 48f))
        }
        val r = base * (1f + pulse)
        val deep = Color(
            red = core.red * 0.18f, green = core.green * 0.18f, blue = core.blue * 0.30f + 0.05f, alpha = 1f
        )

        // ---- atmosphere glow
        drawCircle(
            Brush.radialGradient(
                0.0f to core.copy(alpha = 0.38f), 0.45f to core.copy(alpha = 0.12f), 1f to Color.Transparent,
                center = c, radius = maxR
            ),
            radius = maxR, center = c
        )

        // ---- tick ring (fine HUD scale), slowly turning
        rotate(t * 360f * 1f, c) {
            val n = 90
            val r1 = maxR * 0.93f
            for (i in 0 until n) {
                val a = i * tau / n
                val long = i % 5 == 0
                val r0 = r1 - maxR * (if (long) 0.045f else 0.022f)
                drawLine(
                    core.copy(alpha = if (long) 0.55f else 0.25f),
                    Offset(c.x + cos(a) * r0, c.y + sin(a) * r0),
                    Offset(c.x + cos(a) * r1, c.y + sin(a) * r1),
                    strokeWidth = (if (long) 1.6f else 1f).dp.toPx()
                )
            }
        }

        // ---- dashed ring turning the other way
        rotate(-t * 360f * 2f, c) {
            drawCircle(
                core.copy(alpha = 0.30f), radius = maxR * 0.80f, center = c,
                style = Stroke(1.2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 14f)))
            )
        }

        // ---- radar sweep: a fading comet tail on a ring
        rotate(t * 360f * speed * 3f, c) {
            drawCircle(
                Brush.sweepGradient(
                    0.0f to Color.Transparent, 0.70f to Color.Transparent, 0.98f to core.copy(alpha = 0.85f),
                    1f to Color.White.copy(alpha = 0.95f), center = c
                ),
                radius = maxR * 0.70f, center = c, style = Stroke(2.6.dp.toPx(), cap = StrokeCap.Round)
            )
        }

        // ---- two thick arc segments
        for (i in 0..1) {
            val rr = maxR * (0.62f + i * 0.04f)
            val dir = if (i == 0) 1f else -1f
            for (k in 0..1) {
                drawArc(
                    core.copy(alpha = 0.75f - i * 0.25f),
                    startAngle = t * 360f * (2 + i) * dir + k * 180f, sweepAngle = 48f - i * 14f, useCenter = false,
                    topLeft = c - Offset(rr, rr), size = Size(rr * 2, rr * 2),
                    style = Stroke((3f - i).dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }

        // ---- audio bars around the sphere
        if (phase == Phase.LISTENING || phase == Phase.SPEAKING) {
            val bars = 72
            val r0 = r * 1.10f
            val amp = if (phase == Phase.LISTENING) (0.15f + smooth * 1.1f) else 0.55f
            for (i in 0 until bars) {
                val a = i * tau / bars
                val wob = 0.5f + 0.5f * sin(a * 5f + t * tau * 60f) * cos(a * 3f - t * tau * 36f)
                val len = maxR * 0.16f * amp * (0.25f + wob)
                drawLine(
                    core.copy(alpha = 0.55f + 0.4f * wob),
                    Offset(c.x + cos(a) * r0, c.y + sin(a) * r0),
                    Offset(c.x + cos(a) * (r0 + len), c.y + sin(a) * (r0 + len)),
                    strokeWidth = 2.2.dp.toPx(), cap = StrokeCap.Round
                )
            }
        }

        // ---- the sphere itself
        val disc = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, r)) }
        clipPath(disc) {
            // body: dark edges, bright heart
            drawCircle(
                Brush.radialGradient(
                    0.0f to core.copy(alpha = 0.95f), 0.55f to core.copy(alpha = 0.55f).compositeOver(deep), 1f to deep,
                    center = c, radius = r
                ),
                radius = r, center = c
            )
            // swirling plasma: soft light blobs that drift and add up
            for (k in 0 until 6) {
                val a = t * tau * (1 + k % 3) * (if (k % 2 == 0) 1f else -1f) * speed.coerceAtMost(2f) + k * 1.1f
                val d = r * (0.18f + 0.30f * (0.5f + 0.5f * sin(t * tau * (2 + k) + k)))
                val p = Offset(c.x + cos(a) * d, c.y + sin(a * 1.3f) * d)
                val br = r * (0.50f + 0.14f * sin(t * tau * (3 + k) + k * 2f))
                val tint = if (k % 2 == 0) core else Color.White
                drawCircle(
                    Brush.radialGradient(
                        0f to tint.copy(alpha = if (k % 2 == 0) 0.55f else 0.22f), 1f to Color.Transparent,
                        center = p, radius = br
                    ),
                    radius = br, center = p, blendMode = BlendMode.Plus
                )
            }
            // fine lightning-like filaments
            rotate(t * 360f * 3f, c) {
                for (k in 0 until 3) {
                    val sa = k * 120f + 20f
                    drawArc(
                        Color.White.copy(alpha = 0.25f), startAngle = sa, sweepAngle = 40f, useCenter = false,
                        topLeft = c - Offset(r * 0.6f, r * 0.6f), size = Size(r * 1.2f, r * 1.2f),
                        style = Stroke(1.dp.toPx(), cap = StrokeCap.Round), blendMode = BlendMode.Plus
                    )
                }
            }
            // glass: bright Fresnel rim
            drawCircle(
                Brush.radialGradient(
                    0.0f to Color.Transparent, 0.72f to Color.Transparent, 0.93f to core.copy(alpha = 0.55f),
                    1f to Color.White.copy(alpha = 0.85f), center = c, radius = r
                ),
                radius = r, center = c
            )
            // shade the lower edge so it reads as a ball
            drawCircle(
                Brush.radialGradient(
                    0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.45f),
                    center = c - Offset(r * 0.35f, r * 0.40f), radius = r * 1.7f
                ),
                radius = r, center = c
            )
            // specular highlight
            val hp = c - Offset(r * 0.34f, r * 0.40f)
            drawCircle(
                Brush.radialGradient(0f to Color.White.copy(alpha = 0.85f), 1f to Color.Transparent, center = hp, radius = r * 0.30f),
                radius = r * 0.30f, center = hp
            )
        }
        drawCircle(Color.White.copy(alpha = 0.30f), radius = r, center = c, style = Stroke(1.dp.toPx()))

        // ---- orbiting particles
        for (k in 0 until 14) {
            val orbit = maxR * (0.56f + 0.34f * ((k * 37) % 10) / 10f)
            val a = t * tau * (1 + k % 4) * (if (k % 2 == 0) 1f else -1f) + k * 0.9f
            val p = Offset(c.x + cos(a) * orbit, c.y + sin(a) * orbit)
            val s = (1.2f + (k % 3) * 0.8f).dp.toPx()
            drawCircle(core.copy(alpha = 0.25f), radius = s * 3f, center = p)
            drawCircle(Color.White.copy(alpha = 0.9f), radius = s, center = p)
        }
    }
}

private fun Color.compositeOver(bg: Color): Color {
    val a = alpha + bg.alpha * (1f - alpha)
    if (a <= 0f) return Color.Transparent
    fun m(f: Float, b: Float) = (f * alpha + b * bg.alpha * (1f - alpha)) / a
    return Color(m(red, bg.red), m(green, bg.green), m(blue, bg.blue), a)
}
