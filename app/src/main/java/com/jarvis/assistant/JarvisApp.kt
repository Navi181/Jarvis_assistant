package com.jarvis.assistant

import android.app.Application
import com.jarvis.assistant.core.Assistant
import com.jarvis.assistant.core.CrashLog
import com.jarvis.assistant.core.Prefs

class JarvisApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        CrashLog.install(this)
        Assistant.init(this)
    }
}
