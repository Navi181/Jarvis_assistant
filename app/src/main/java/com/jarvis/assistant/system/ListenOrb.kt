package com.jarvis.assistant.system

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
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
import com.jarvis.assistant.ui.JarvisOrb

/**
 * A small copy of the in-app orb that floats at the top of the screen while Jarvis is listening and you are in another app
 * (after you say "Jarvis"), so you know when to speak. It is the very same orb, drawn smaller. Needs "Display over other apps".
 */
object ListenOrb {
    @Volatile
    var foreground = false

    private var view: ComposeView? = null
    private var owner: Owner? = null

    fun update(ctx: Context, phase: Phase) {
        val want = Prefs.listenOrb && !foreground && phase == Phase.LISTENING && canShow(ctx)
        if (want) show(ctx.applicationContext) else hide()
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
            val size = (density * 112).toInt()
            val lp = WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            lp.y = (density * 48).toInt()
            val o = Owner().also { it.start() }
            val v = ComposeView(ctx)
            v.setViewTreeLifecycleOwner(o)
            v.setViewTreeViewModelStoreOwner(o)
            v.setViewTreeSavedStateRegistryOwner(o)
            v.setContent {
                val phase by Assistant.phase.collectAsState()
                val level by Assistant.level.collectAsState()
                JarvisOrb(phase, level, Modifier.fillMaxSize())
            }
            wm.addView(v, lp)
            owner = o
            view = v
        } catch (e: Exception) {
            view = null
            owner = null
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
