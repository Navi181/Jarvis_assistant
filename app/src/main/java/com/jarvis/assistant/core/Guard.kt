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

    /** Name of the WhatsApp group to pick in the share list, or "" for a normal chat that is already open. */
    @Volatile
    var group: String = ""
        private set

    fun arm() {
        group = ""
        until = System.currentTimeMillis() + 25_000L
    }

    /** Groups can't be opened by link, so Jarvis finds the group in WhatsApp's own list; that takes longer. */
    fun armGroup(name: String) {
        group = name
        until = System.currentTimeMillis() + 50_000L
    }

    fun disarm() {
        until = 0L
        group = ""
    }

    fun armed(): Boolean = System.currentTimeMillis() < until
}

object AppPerms {
    /** Every runtime permission Jarvis uses. */
    val all: Array<String> = buildList {
        add(android.Manifest.permission.RECORD_AUDIO)
        add(android.Manifest.permission.READ_CONTACTS)
        add(android.Manifest.permission.CALL_PHONE)
        add(android.Manifest.permission.READ_CALENDAR)
        add(android.Manifest.permission.WRITE_CALENDAR)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            add(android.Manifest.permission.POST_NOTIFICATIONS)
            add(android.Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) add(android.Manifest.permission.ACCESS_MEDIA_LOCATION)
    }.toTypedArray()
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
