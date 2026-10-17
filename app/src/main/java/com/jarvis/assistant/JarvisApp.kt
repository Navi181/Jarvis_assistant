package com.jarvis.assistant

import android.app.Application
import com.jarvis.assistant.core.Assistant
import com.jarvis.assistant.core.CrashLog
import com.jarvis.assistant.core.Prefs
import com.jarvis.assistant.system.ListenOrb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class JarvisApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        CrashLog.install(this)
        Assistant.init(this)
        // the small orange orb while Jarvis listens and you are in another app
        CoroutineScope(Dispatchers.Main).launch { Assistant.phase.collect { ListenOrb.update(this@JarvisApp, it) } }
    }
}
