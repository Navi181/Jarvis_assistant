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
import com.jarvis.assistant.ui.JarvisUi

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    override fun onResume() {
        super.onResume()
        // If the app was closed or restarted mid-session, pin the screen again.
        if (FocusLock.active()) runCatching { startLockTask() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    /** Power-button / headset / "assist" gesture launches go straight to listening. */
    private fun handle(i: Intent?) {
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
