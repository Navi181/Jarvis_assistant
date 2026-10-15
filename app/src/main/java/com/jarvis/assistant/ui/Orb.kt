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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.core.Phase
import kotlin.math.PI
import kotlin.math.sin

/**
 * The arc-reactor style orb. Idle: slow breathing. Listening: reacts to your voice loudness.
 * Thinking: violet with a fast scanning arc. Speaking: pulsing rings.
 */
@Composable
fun JarvisOrb(phase: Phase, level: Float, modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "orb")
    val spin by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(14000, easing = LinearEasing)), label = "spin")
    val spinBack by inf.animateFloat(360f, 0f, infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "spinBack")
    val breathe by inf.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathe"
    )
    val wave by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "wave")

    val smooth by animateFloatAsState(level, spring(stiffness = Spring.StiffnessLow), label = "level")
    val core by animateColorAsState(
        when (phase) {
            Phase.IDLE -> Cyan
            Phase.LISTENING -> Color(0xFF5EEAD4)
            Phase.THINKING -> Violet
            Phase.SPEAKING -> Blue
        },
        tween(700), label = "core"
    )

    Canvas(modifier.aspectRatio(1f)) {
        val c = center
        val maxR = size.minDimension / 2f
        val base = maxR * 0.42f

        val energy = when (phase) {
            Phase.IDLE -> 0.03f + 0.05f * breathe
            Phase.LISTENING -> 0.06f + smooth * 0.38f
            Phase.THINKING -> 0.06f + 0.08f * breathe
            Phase.SPEAKING -> 0.10f + 0.10f * (0.5f + 0.5f * sin(wave * 2f * PI.toFloat() * 2f))
        }
        val r = base * (1f + energy)

        // soft outer glow
        drawCircle(
            brush = Brush.radialGradient(
                listOf(core.copy(alpha = 0.50f), core.copy(alpha = 0.10f), Color.Transparent),
                center = c, radius = r * 2.4f
            ),
            radius = r * 2.4f, center = c
        )

        // dotted outer ring
        rotate(spinBack, c) {
            drawCircle(
                color = core.copy(alpha = 0.28f),
                radius = maxR * 0.96f, center = c,
                style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 16f)))
            )
        }

        // rotating arc segments
        for (i in 0..2) {
            val rr = base * (1.34f + i * 0.24f) + r * 0.05f * (if (phase == Phase.LISTENING) smooth else breathe)
            val dir = if (i % 2 == 0) 1f else -1f
            val sweep = 62f + i * 34f
            val width = (3.2f - i * 0.7f).dp.toPx()
            for (k in 0..1) {
                drawArc(
                    color = core.copy(alpha = 0.85f - i * 0.2f),
                    startAngle = spin * (i + 1) * dir + k * 180f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = c - Offset(rr, rr),
                    size = Size(rr * 2, rr * 2),
                    style = Stroke(width, cap = StrokeCap.Round)
                )
            }
        }

        // thinking: fast scanner
        if (phase == Phase.THINKING) {
            val rr = base * 1.18f
            drawArc(
                color = Color.White.copy(alpha = 0.9f),
                startAngle = wave * 720f, sweepAngle = 90f, useCenter = false,
                topLeft = c - Offset(rr, rr), size = Size(rr * 2, rr * 2),
                style = Stroke(3.dp.toPx(), cap = StrokeCap.Round)
            )
        }

        // ripples while listening / speaking
        if (phase == Phase.LISTENING || phase == Phase.SPEAKING) {
            for (k in 0..1) {
                val p = (wave + k * 0.5f) % 1f
                drawCircle(
                    color = core.copy(alpha = (1f - p) * 0.35f),
                    radius = r + (maxR * 0.92f - r) * p, center = c,
                    style = Stroke(2.dp.toPx())
                )
            }
        }

        // glowing core sphere
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color.White.copy(alpha = 0.95f), core, core.copy(alpha = 0.35f)),
                center = c - Offset(r * 0.28f, r * 0.28f), radius = r * 1.35f
            ),
            radius = r, center = c
        )
        drawCircle(color = Color.White.copy(alpha = 0.35f), radius = r, center = c, style = Stroke(1.5.dp.toPx()))
    }
}
