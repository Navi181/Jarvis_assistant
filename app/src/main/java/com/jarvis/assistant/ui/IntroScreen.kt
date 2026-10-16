package com.jarvis.assistant.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.core.Prefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Opening sequence: the arc reactor draws itself in over the night sky, the name fades in,
 * then everything dissolves into the app. Tap anywhere to skip.
 */
@Composable
fun IntroScreen(onDone: () -> Unit) {
    val draw = remember { Animatable(0f) }
    val title = remember { Animatable(0f) }
    val sub = remember { Animatable(0f) }
    val out = remember { Animatable(1f) }
    var finished by remember { mutableStateOf(false) }

    fun finish() {
        if (!finished) {
            finished = true
            onDone()
        }
    }

    LaunchedEffect(Unit) {
        launch { draw.animateTo(1f, tween(1600, easing = FastOutSlowInEasing)) }
        delay(700)
        launch { title.animateTo(1f, tween(1000)) }
        delay(600)
        launch { sub.animateTo(1f, tween(800)) }
        delay(1500)
        out.animateTo(0f, tween(500))
        finish()
    }

    val name = Prefs.userName.substringBefore(' ').ifBlank { "" }
    val greeting = if (name.isNotBlank()) "Welcome back, $name" else "All systems online"

    Box(
        Modifier
            .fillMaxSize()
            .alpha(out.value)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { finish() },
        contentAlignment = Alignment.Center
    ) {
        Backdrop()
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Canvas(Modifier.size(200.dp)) {
                val p = draw.value
                val c = center
                val glowR = size.minDimension * 0.5f * p
                drawCircle(
                    Brush.radialGradient(listOf(Cyan.copy(alpha = 0.35f * p), Color.Transparent), c, glowR.coerceAtLeast(1f)),
                    radius = glowR.coerceAtLeast(1f), center = c
                )
                val stroke = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                val r1 = size.minDimension * 0.44f
                val r2 = size.minDimension * 0.30f
                val r3 = size.minDimension * 0.17f
                // three arcs of the outer ring sweep in, then the inner ring, then the core
                for (k in 0 until 3) {
                    drawArc(
                        Cyan.copy(alpha = p), -90f + k * 120f + 14f, 92f * p, false,
                        Offset(c.x - r1, c.y - r1), Size(r1 * 2, r1 * 2), style = stroke
                    )
                }
                drawArc(
                    Blue.copy(alpha = p), 90f - 360f * p, 360f * p, false,
                    Offset(c.x - r2, c.y - r2), Size(r2 * 2, r2 * 2), style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                )
                drawCircle(
                    Brush.radialGradient(listOf(Color.White, Cyan, Blue.copy(alpha = 0.2f)), c, r3),
                    radius = r3 * p, center = c
                )
            }
            Text(
                "JARVIS",
                color = Ink, fontSize = 38.sp, fontWeight = FontWeight.Light,
                letterSpacing = (22 - 12 * title.value).sp,
                modifier = Modifier.padding(top = 28.dp).alpha(title.value)
            )
            Text(
                greeting,
                color = Cyan, fontSize = 15.sp, textAlign = TextAlign.Center,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(top = 14.dp).alpha(sub.value)
            )
            Text(
                "Tap to skip",
                color = InkDim, fontSize = 11.sp,
                modifier = Modifier.padding(top = 36.dp).alpha(0.5f * sub.value)
            )
        }
    }
}
