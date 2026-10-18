package com.jarvis.assistant.system

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.style.TextOverflow
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
import com.jarvis.assistant.MainActivity
import com.jarvis.assistant.core.Assistant
import com.jarvis.assistant.core.Msg
import com.jarvis.assistant.core.Phase
import com.jarvis.assistant.core.Prefs
import com.jarvis.assistant.ui.Cyan
import com.jarvis.assistant.ui.Ink
import com.jarvis.assistant.ui.InkDim
import com.jarvis.assistant.ui.JarvisOrb
import com.jarvis.assistant.ui.Outline
import com.jarvis.assistant.ui.Panel

/**
 * Jarvis floating over other apps: the same orb as in the app, smaller, while it listens, thinks and speaks,
 * and the answer in a small card just below it so you can read it. Swipe up on it to send it away; tap it to open Jarvis.
 * If you say nothing it goes by itself after about 10 seconds. Needs "Display over other apps".
 */
object ListenOrb {
    @Volatile
    var foreground = false

    private val main = Handler(Looper.getMainLooper())
    private var view: ComposeView? = null
    private var owner: Owner? = null
    private var appCtx: Context? = null
    private var cardText by mutableStateOf("")
    private var cardUntil = 0L
    private var dismissed = false
    private var lastPhase = Phase.IDLE
    private var seenMessages = 0

    private val recheck = Runnable { appCtx?.let { update(it, Assistant.phase.value) } }

    fun update(ctx: Context, phase: Phase) {
        appCtx = ctx.applicationContext
        if (phase == Phase.LISTENING && lastPhase == Phase.IDLE) {
            // a new conversation: forget the last card
            dismissed = false
            cardText = ""
            cardUntil = 0L
        }
        lastPhase = phase
        val cardOn = cardText.isNotBlank() && System.currentTimeMillis() < cardUntil
        val want = Prefs.listenOrb && !foreground && !dismissed && canShow(ctx) && (phase != Phase.IDLE || cardOn)
        if (want) show(ctx.applicationContext) else hide()
        main.removeCallbacks(recheck)
        if (cardOn && phase == Phase.IDLE) main.postDelayed(recheck, (cardUntil - System.currentTimeMillis()).coerceAtLeast(200L))
    }

    /** Called whenever the conversation changes: a new answer from Jarvis goes into the card. */
    fun onMessages(ctx: Context, list: List<Msg>) {
        val n = list.size
        val last = list.lastOrNull()
        if (n != seenMessages && last != null && !last.fromUser && !foreground) {
            cardText = last.text.trim()
            // long enough to read, not so long that it hangs around
            cardUntil = System.currentTimeMillis() + (cardText.length * 55L).coerceIn(7000L, 30000L)
        }
        seenMessages = n
        update(ctx, Assistant.phase.value)
    }

    private fun dismiss() {
        dismissed = true
        cardText = ""
        cardUntil = 0L
        try { Assistant.cancel() } catch (_: Exception) {}
        hide()
    }

    private fun canShow(ctx: Context) = try {
        Settings.canDrawOverlays(ctx)
    } catch (e: Exception) {
        false
    }

    /** The lifecycle a view needs to be drawn by Compose outside an activity. */
    private class Owner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
        private val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this)
        private val store = ViewModelStore()
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
        override val viewModelStore: ViewModelStore get() = store

        fun start() {
            saved.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun stop() {
            registry.currentState = Lifecycle.State.DESTROYED
            store.clear()
        }
    }

    private fun show(ctx: Context) {
        if (view != null) return
        try {
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val density = ctx.resources.displayMetrics.density
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            lp.y = (density * 40).toInt()
            val o = Owner().also { it.start() }
            val v = ComposeView(ctx)
            v.setViewTreeLifecycleOwner(o)
            v.setViewTreeViewModelStoreOwner(o)
            v.setViewTreeSavedStateRegistryOwner(o)
            v.setContent { Hud(ctx) }
            wm.addView(v, lp)
            owner = o
            view = v
        } catch (e: Exception) {
            view = null
            owner = null
        }
    }

    @androidx.compose.runtime.Composable
    private fun Hud(ctx: Context) {
        val phase by Assistant.phase.collectAsState()
        val level by Assistant.level.collectAsState()
        val text = cardText
        Column(
            Modifier
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        try {
                            ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (_: Exception) {}
                    })
                }
                .pointerInput(Unit) {
                    var drag = 0f
                    detectVerticalDragGestures(
                        onDragStart = { drag = 0f },
                        onVerticalDrag = { _, dy -> drag += dy },
                        onDragEnd = { if (drag < -40.dp.toPx()) dismiss() }
                    )
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            JarvisOrb(phase, level, Modifier.size(104.dp))
            if (text.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Panel.copy(alpha = 0.94f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
                    modifier = Modifier.widthIn(max = 320.dp).padding(horizontal = 12.dp)
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text("JARVIS", color = Cyan, fontSize = 10.sp, letterSpacing = 1.5.sp)
                        Text(
                            text, color = Ink, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 10, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Text("Swipe up to dismiss", color = InkDim, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
    }

    fun hide() {
        val v = view ?: return
        view = null
        try {
            v.disposeComposition()
            (v.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (_: Exception) {
        }
        try {
            owner?.stop()
        } catch (_: Exception) {
        }
        owner = null
    }
}
