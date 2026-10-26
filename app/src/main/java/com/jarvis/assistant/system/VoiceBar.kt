package com.jarvis.assistant.system

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.jarvis.assistant.core.Assistant
import com.jarvis.assistant.core.Phase
import com.jarvis.assistant.core.Prefs
import com.jarvis.assistant.ui.Cyan
import com.jarvis.assistant.ui.Ink
import com.jarvis.assistant.ui.InkDim
import com.jarvis.assistant.ui.JarvisOrb
import com.jarvis.assistant.ui.Panel

/**
 * The floating voice bar: a small orb docked at the screen edge, over every app (needs no extra permission, it rides on the
 * accessibility service). Tap it to open Dictate / Edit / Ask. Dictate types what you say into the box you are in, Edit rewrites
 * the selected text, Ask answers about what is on screen. Drag it up or down to move it.
 */
object VoiceBar {
    @Volatile var appForeground = false

    private var view: ComposeView? = null
    private var owner: Owner? = null
    private var params: WindowManager.LayoutParams? = null

    private class Owner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
        private val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this)
        private val store = ViewModelStore()
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
        override val viewModelStore: ViewModelStore get() = store
        fun start() { saved.performRestore(null); registry.currentState = Lifecycle.State.RESUMED }
        fun stop() { registry.currentState = Lifecycle.State.DESTROYED; store.clear() }
    }

    /** Shows or hides the bar to match the setting and whether Jarvis itself is on screen. */
    fun refresh() {
        val svc = JarvisAccessibilityService.instance
        if (svc != null && Prefs.voiceBar && !appForeground) show(svc) else hide()
    }

    private fun show(ctx: Context) {
        if (view != null) return
        try {
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.END or Gravity.CENTER_VERTICAL
            lp.y = (ctx.resources.displayMetrics.density * 120).toInt()
            val o = Owner().also { it.start() }
            val v = ComposeView(ctx)
            v.setViewTreeLifecycleOwner(o)
            v.setViewTreeViewModelStoreOwner(o)
            v.setViewTreeSavedStateRegistryOwner(o)
            v.setContent { Bar(wm) }
            wm.addView(v, lp)
            owner = o; view = v; params = lp
        } catch (e: Exception) {
            view = null; owner = null; params = null
        }
    }

    fun hide() {
        val v = view ?: return
        view = null
        try {
            v.disposeComposition()
            (v.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (_: Exception) {}
        try { owner?.stop() } catch (_: Exception) {}
        owner = null; params = null
    }

    @Composable
    private fun Bar(wm: WindowManager) {
        val phase by Assistant.phase.collectAsState()
        val level by Assistant.level.collectAsState()
        var open by remember { mutableStateOf(false) }
        val mode = Assistant.layerMode
        val listening = phase == Phase.LISTENING

        fun go(m: String) { open = false; Assistant.listenIn(m) }

        Surface(
            shape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp),
            color = Panel.copy(alpha = 0.94f),
            border = BorderStroke(1.dp, (if (listening) Cyan else Color.White).copy(alpha = 0.25f)),
            modifier = Modifier.animateContentSize()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 6.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
                Box(
                    Modifier
                        .size(48.dp)
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = {
                                if (phase != Phase.IDLE) Assistant.cancel() else open = !open
                            })
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                params?.let { p -> p.y += drag.y.toInt(); try { wm.updateViewLayout(view, p) } catch (_: Exception) {} }
                            }
                        }
                ) { JarvisOrb(phase, level, Modifier.size(48.dp)) }
                AnimatedVisibility(visible = open, enter = fadeIn() + expandHorizontally(), exit = fadeOut() + shrinkHorizontally()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Chip("Dictate") { go("dictate") }
                        Chip("Edit") { go("edit") }
                        Chip("Ask") { go("ask") }
                    }
                }
                if (listening && mode != null && !open) {
                    Text(
                        when (mode) { "dictate" -> "Dictating…"; "edit" -> "Say the change…"; else -> "Ask away…" },
                        color = Ink, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            }
        }
    }

    @Composable
    private fun Chip(label: String, onClick: () -> Unit) {
        Text(
            label, color = Ink, fontSize = 13.sp,
            modifier = Modifier
                .padding(horizontal = 3.dp)
                .clip(RoundedCornerShape(16.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}
