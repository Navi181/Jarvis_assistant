package com.jarvis.assistant.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer

/** Fades and floats a thing in the first time it shows up. [delayMs] staggers neighbours. */
fun Modifier.appear(delayMs: Int = 0, rise: Float = 24f): Modifier = composed {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { p.animateTo(1f, tween(420, delayMs, FastOutSlowInEasing)) }
    graphicsLayer {
        alpha = p.value
        translationY = (1f - p.value) * rise * density
        val s = 0.97f + 0.03f * p.value
        scaleX = s; scaleY = s
    }
}
