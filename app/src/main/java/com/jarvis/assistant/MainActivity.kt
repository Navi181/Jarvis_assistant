package com.jarvis.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import com.jarvis.assistant.core.Assistant
import com.jarvis.assistant.core.FocusLock
import com.jarvis.assistant.core.Prefs
import com.jarvis.assistant.core.Updater
import com.jarvis.assistant.core.Phase
import com.jarvis.assistant.ui.JarvisUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var wakeSession = false
    private val uiScope = CoroutineScope(Dispatchers.Main)
    private var phaseJob: Job? = null

    /** Opened by the wake word while the phone is locked: show above the lock screen (it never unlocks the phone), then step away. */
    private fun applyWake(i: Intent?) {
        if (i?.getBooleanExtra("fromWake", false) != true) return
        wakeSession = true
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
    }

    private fun endWake() {
        wakeSession = false
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(false)
            setTurnScreenOn(false)
        }
        moveTaskToBack(true)
    }

    override fun onDestroy() {
        phaseJob?.cancel()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyWake(intent)
        phaseJob = uiScope.launch {
            var seen = false
            Assistant.phase.collect { ph ->
                if (wakeSession) {
                    if (ph != Phase.IDLE) seen = true
                    else if (seen) {
                        seen = false
                        delay(1800)
                        if (Assistant.phase.value == Phase.IDLE && wakeSession) endWake()
                    }
                }
            }
        }
        enableEdgeToEdge()
        // During a focus session the back button does nothing.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!FocusLock.active()) moveTaskToBack(true)
            }
        })
        setContent { JarvisUi() }
        if (savedInstanceState == null) handle(intent)
        Updater.autoCheck(applicationContext)
    }

    override fun onPause() {
        super.onPause()
        com.jarvis.assistant.system.ListenOrb.foreground = false
    }

    override fun onResume() {
        super.onResume()
        com.jarvis.assistant.system.ListenOrb.foreground = true
        com.jarvis.assistant.system.ListenOrb.hide()
        // If the app was closed or restarted mid-session, pin the screen again.
        if (FocusLock.active()) runCatching { startLockTask() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyWake(intent)
        handle(intent)
    }

    /** Power-button / headset / "assist" gesture launches go straight to listening. */
    private fun handle(i: Intent?) {
        if (i?.getBooleanExtra("askPerms", false) == true) {
            androidx.core.app.ActivityCompat.requestPermissions(this, com.jarvis.assistant.core.AppPerms.all, 77)
        }
        val a = i?.action
        val wantsListen = a == Intent.ACTION_ASSIST ||
            a == Intent.ACTION_VOICE_COMMAND ||
            a == "com.jarvis.assistant.LISTEN" ||
            i?.getBooleanExtra("listen", false) == true
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (wantsListen && Prefs.onboarded && hasMic) {
            window.decorView.postDelayed({ Assistant.listen() }, 250)
        }
    }
}
