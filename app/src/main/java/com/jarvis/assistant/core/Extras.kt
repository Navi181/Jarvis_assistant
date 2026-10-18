package com.jarvis.assistant.core

import android.content.Context
import android.content.SharedPreferences

/** Newer small settings (Jarvis voice, background), kept in their own file so they never depend on anything else. */
object Extras {
    private var sp: SharedPreferences? = null

    fun init(ctx: Context) {
        if (sp == null) sp = ctx.applicationContext.getSharedPreferences("jarvis_extras", Context.MODE_PRIVATE)
    }

    var aiVoice: Boolean
        get() = sp?.getBoolean("aiVoice", true) ?: true
        set(v) { sp?.edit()?.putBoolean("aiVoice", v)?.apply() }

    var aiVoiceName: String
        get() = sp?.getString("aiVoiceName", "") ?: ""
        set(v) { sp?.edit()?.putString("aiVoiceName", v)?.apply() }

    var backdrop: String
        get() = sp?.getString("backdrop", "") ?: ""
        set(v) { sp?.edit()?.putString("backdrop", v)?.apply() }
}
