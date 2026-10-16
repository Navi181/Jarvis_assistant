package com.jarvis.assistant.core

import android.content.Context
import android.provider.Settings

/** True while a focus session is running. Checked by the app, the back button and the accessibility service. */
object FocusLock {
    fun active(): Boolean = Prefs.focusEnd > System.currentTimeMillis()
}

/** Set just before WhatsApp is opened, so the accessibility service knows it may press Send. */
object WhatsAppSender {
    @Volatile
    private var until = 0L

    fun arm() {
        until = System.currentTimeMillis() + 25_000L
    }

    fun disarm() {
        until = 0L
    }

    fun armed(): Boolean = System.currentTimeMillis() < until
}

object A11y {
    /** Whether the user has switched Jarvis on in Android's accessibility settings. */
    fun enabled(ctx: Context): Boolean = try {
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().contains(ctx.packageName)
    } catch (e: Exception) {
        false
    }
}
