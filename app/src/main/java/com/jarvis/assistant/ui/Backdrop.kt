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
import androidx.compose.ui.graphics.Path
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jarvis.assistant.core.Extras
import com.jarvis.assistant.core.Prefs
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

/** The chosen background. Changing it takes effect at once, everywhere. */
object BackdropState {
    val styles = listOf(
        "stars" to "Starfield",
        "doodles" to "Doodles",
        "aurora" to "Aurora",
        "grid" to "HUD grid",
        "rain" to "Data rain",
        "plain" to "Plain black"
    )
    var style by mutableStateOf("stars")
        private set
    private var loaded = false

    fun load() {
        if (!loaded) {
            loaded = true
            style = Extras.backdrop.ifBlank { "stars" }
        }
    }

    fun pick(s: String) {
        style = s
        Extras.backdrop = s
    }
}

private class Star(val x: Float, val y: Float, val r: Float, val phase: Float, val twinkle: Int, val speed: Int)
private class Doodle(val x: Float, val y: Float, val kind: Int, val size: Float, val speed: Int, val spin: Int, val color: Color, val phase: Float)
private class Streak(val x: Float, val len: Float, val speed: Int, val phase: Float, val alpha: Float)

private val DOODLE_COLORS = listOf(Color(0xFF22D3EE), Color(0xFFE6F1FF), Color(0xFF8B5CF6), Color(0xFF3B82F6), Color(0xFFFFB454))

/** Moving backgrounds (all loop seamlessly and stay dim so text is always easy to read). */
@Composable
fun Backdrop(modifier: Modifier = Modifier) {
    BackdropState.load()
    val style = BackdropState.style
    val stars = remember { List(140) { Star(Random.nextFloat(), Random.nextFloat(), Random.nextFloat() * 1.4f + 0.4f, Random.nextFloat() * 6.28f, 20 + Random.nextInt(40), 1 + Random.nextInt(3)) } }
    val doodles = remember { List(16) { Doodle(Random.nextFloat(), Random.nextFloat(), it % 8, 14f + Random.nextFloat() * 22f, 1 + Random.nextInt(2), 1 + Random.nextInt(3), DOODLE_COLORS[Random.nextInt(DOODLE_COLORS.size)], Random.nextFloat() * 6.28f) } }
    val streaks = remember { List(34) { Streak(Random.nextFloat(), 0.08f + Random.nextFloat() * 0.18f, 2 + Random.nextInt(4), Random.nextFloat(), 0.25f + Random.nextFloat() * 0.4f) } }
    val inf = rememberInfiniteTransition(label = "backdrop")
    val t by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(60000, easing = LinearEasing)), label = "t")
    val tau = (2 * PI).toFloat()

    Canvas(modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val c = Offset(w * 0.5f, h * 0.4f)
        drawRect(Brush.verticalGradient(listOf(Bg, BgDeep, Bg)))

        when (style) {
            "stars" -> {
                for (s in stars) {
                    val y = ((s.y + t * s.speed) % 1f)
                    val a = 0.2f + 0.6f * abs(sin(t * tau * s.twinkle + s.phase))
                    val p = Offset(s.x * w, y * h)
                    drawCircle(Color.White.copy(alpha = a * 0.75f), s.r.dp.toPx() * 0.5f * (0.7f + 0.3f * s.speed), p)
                    if (s.speed == 3) drawLine(Color.White.copy(alpha = a * 0.18f), p, Offset(p.x, p.y - 10.dp.toPx()), strokeWidth = 0.8.dp.toPx())
                }
                // an occasional shooting star
                val k = (t * 4f) % 1f
                if (k < 0.18f) {
                    val f = k / 0.18f
                    val sx = w * (0.15f + 0.5f * ((t * 4f).toInt() % 3) / 3f) + f * w * 0.35f
                    val sy = h * 0.08f + f * h * 0.22f
                    drawLine(
                        Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.85f * (1f - f))), Offset(sx - 70.dp.toPx(), sy - 40.dp.toPx()), Offset(sx, sy)),
                        Offset(sx - 70.dp.toPx(), sy - 40.dp.toPx()), Offset(sx, sy), strokeWidth = 1.6.dp.toPx()
                    )
                }
            }
            "doodles" -> {
                for (d in doodles) {
                    val y = 1.1f - ((d.y + t * d.speed) % 1.2f)
                    val cx = d.x * w + sin(t * tau * d.speed + d.phase) * 14.dp.toPx()
                    val cy = y * h
                    val r = d.size.dp.toPx()
                    val st = Stroke(1.4.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    val col = d.color.copy(alpha = 0.20f)
                    rotate(t * 360f * d.spin * (if (d.kind % 2 == 0) 1 else -1), Offset(cx, cy)) {
                        when (d.kind) {
                            0 -> drawCircle(col, r, Offset(cx, cy), style = st)
                            1 -> drawPath(poly(cx, cy, r, 3), col, style = st)
                            2 -> drawRect(col, Offset(cx - r * 0.8f, cy - r * 0.8f), Size(r * 1.6f, r * 1.6f), style = st)
                            3 -> {
                                drawLine(col, Offset(cx - r, cy), Offset(cx + r, cy), 1.4.dp.toPx())
                                drawLine(col, Offset(cx, cy - r), Offset(cx, cy + r), 1.4.dp.toPx())
                            }
                            4 -> drawPath(squiggle(cx, cy, r), col, style = st)
                            5 -> drawPath(star(cx, cy, r), col, style = st)
                            6 -> drawPath(poly(cx, cy, r, 6), col, style = st)
                            else -> drawArc(col, 20f, 250f, false, Offset(cx - r, cy - r), Size(r * 2, r * 2), style = st)
                        }
                    }
                }
            }
            "aurora" -> {
                val blobs = listOf(
                    Triple(Color(0xFF22D3EE), 1, 0f), Triple(Color(0xFF8B5CF6), 2, 1.7f),
                    Triple(Color(0xFF3B82F6), 1, 3.1f), Triple(Color(0xFF34D399), 3, 4.4f)
                )
                blobs.forEach { (col, k, ph) ->
                    val bx = w * (0.5f + 0.38f * sin(t * tau * k + ph))
                    val by = h * (0.35f + 0.28f * cos(t * tau * (k + 1) * 0.5f * 2f + ph))
                    val r = max(w, h) * 0.55f
                    drawCircle(Brush.radialGradient(listOf(col.copy(alpha = 0.20f), Color.Transparent), Offset(bx, by), r), r, Offset(bx, by))
                }
            }
            "grid" -> {
                val step = 38.dp.toPx()
                val off = (t * 6f * step) % step
                val col = Color(0xFF22D3EE).copy(alpha = 0.07f)
                var x = -step + off
                while (x < w + step) { drawLine(col, Offset(x, 0f), Offset(x, h), 1f); x += step }
                var y = -step + off
                while (y < h + step) { drawLine(col, Offset(0f, y), Offset(w, y), 1f); y += step }
                val sy = h * ((t * 3f) % 1f)
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color(0xFF22D3EE).copy(alpha = 0.10f), Color.Transparent), sy - 90.dp.toPx(), sy + 90.dp.toPx()), Offset(0f, sy - 90.dp.toPx()), Size(w, 180.dp.toPx()))
                for (i in 0 until 14) {
                    val gx = (((i * 53) % 17) / 17f) * w
                    val gy = (((i * 31) % 13) / 13f) * h
                    val a = 0.15f + 0.5f * abs(sin(t * tau * (1 + i % 3) + i))
                    drawCircle(Color(0xFF22D3EE).copy(alpha = a * 0.6f), 2.dp.toPx(), Offset(gx - gx % step + off, gy - gy % step + off))
                }
            }
            "rain" -> {
                for (s in streaks) {
                    val head = ((s.phase + t * s.speed) % 1.3f) - 0.15f
                    val x = s.x * w
                    val y1 = head * h
                    val y0 = y1 - s.len * h
                    drawLine(
                        Brush.verticalGradient(listOf(Color.Transparent, Color(0xFF22D3EE).copy(alpha = s.alpha)), y0, y1),
                        Offset(x, y0), Offset(x, y1), strokeWidth = 1.6.dp.toPx()
                    )
                    drawCircle(Color.White.copy(alpha = s.alpha), 1.4.dp.toPx(), Offset(x, y1))
                }
            }
            else -> {}
        }
        // soft vignette so the edges are always deep and calm
        drawRect(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)), c, max(w, h) * 0.8f))
    }
}

private fun poly(cx: Float, cy: Float, r: Float, n: Int): Path = Path().apply {
    for (i in 0 until n) {
        val a = (i * 2 * PI / n - PI / 2).toFloat()
        val x = cx + r * cos(a)
        val y = cy + r * sin(a)
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

private fun star(cx: Float, cy: Float, r: Float): Path = Path().apply {
    for (i in 0 until 10) {
        val a = (i * PI / 5 - PI / 2).toFloat()
        val rr = if (i % 2 == 0) r else r * 0.42f
        val x = cx + rr * cos(a)
        val y = cy + rr * sin(a)
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

private fun squiggle(cx: Float, cy: Float, r: Float): Path = Path().apply {
    moveTo(cx - r, cy)
    for (i in 0 until 4) {
        val x0 = cx - r + i * r * 0.5f
        quadraticBezierTo(x0 + r * 0.125f, cy + (if (i % 2 == 0) -r * 0.5f else r * 0.5f), x0 + r * 0.25f, cy)
    }
}
