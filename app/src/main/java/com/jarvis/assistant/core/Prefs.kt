package com.jarvis.assistant.core

import android.content.Context
import android.content.SharedPreferences

/** Tiny settings store. Keys never leave the phone except to the AI provider you choose. */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(c: Context) {
        sp = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
    }

    private fun str(k: String, d: String = "") = sp.getString(k, d) ?: d
    private fun put(k: String, v: String) = sp.edit().putString(k, v).apply()

    /** "gemini", "groq" or "claude": the one tried first. The others with a key are used as backups. */
    var provider: String
        get() = str("provider", "gemini")
        set(v) = put("provider", v)

    var geminiKey: String
        get() = str("geminiKey")
        set(v) = put("geminiKey", v.trim())

    var groqKey: String
        get() = str("groqKey")
        set(v) = put("groqKey", v.trim())

    var claudeKey: String
        get() = str("claudeKey")
        set(v) = put("claudeKey", v.trim())

    var picoKey: String
        get() = str("picoKey")
        set(v) = put("picoKey", v.trim())

    /** Name of the chosen text-to-speech voice ("" = pick a deep male voice automatically). */
    var voiceName: String
        get() = str("voiceName")
        set(v) = put("voiceName", v)

    /** Lower = deeper. 1.0 is the voice's natural pitch. */
    var voicePitch: Float
        get() = sp.getFloat("voicePitch3", 0.66f)
        set(v) = sp.edit().putFloat("voicePitch3", v).apply()

    /** Plays the voice back deeper so it sounds like a man, whatever the phone's speech engine offers. */
    var deepVoice: Boolean
        get() = sp.getBoolean("deepVoice", true)
        set(v) = sp.edit().putBoolean("deepVoice", v).apply()

    var voiceRate: Float
        get() = sp.getFloat("voiceRate2", 0.92f)
        set(v) = sp.edit().putFloat("voiceRate2", v).apply()

    var callMe: String
        get() = str("callMe", "sir")
        set(v) = put("callMe", v)

    var userName: String
        get() = str("userName")
        set(v) = put("userName", v.trim())

    var email: String
        get() = str("email")
        set(v) = put("email", v.trim())

    var signedIn: Boolean
        get() = sp.getBoolean("signedIn", false)
        set(v) = sp.edit().putBoolean("signedIn", v).apply()

    var handsFree: Boolean
        get() = sp.getBoolean("handsFree", false)
        set(v) = sp.edit().putBoolean("handsFree", v).apply()

    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false)
        set(v) = sp.edit().putBoolean("onboarded", v).apply()

    var homeCity: String
        get() = str("homeCity")
        set(v) = put("homeCity", v.trim())

    /** Keep the mic open for follow-ups after answers (conversation mode). */
    var continuous: Boolean
        get() = sp.getBoolean("continuous", true)
        set(v) = sp.edit().putBoolean("continuous", v).apply()

    var lastUpdateCheck: Long
        get() = sp.getLong("lastUpdateCheck", 0L)
        set(v) = sp.edit().putLong("lastUpdateCheck", v).apply()

    var autoUpdate: Boolean
        get() = sp.getBoolean("autoUpdate", true)
        set(v) = sp.edit().putBoolean("autoUpdate", v).apply()

    // ---- personalisation
    /** witty | formal | friendly | sarcastic */
    var personality: String
        get() = str("personality", "witty")
        set(v) = put("personality", v)

    /** short | normal | detailed */
    var answerLen: String
        get() = str("answerLen", "short")
        set(v) = put("answerLen", v)

    /** Language for listening and speaking, e.g. en-IN, hi-IN. */
    var language: String
        get() = str("language", "en-IN")
        set(v) = put("language", v)

    /** Small orb on screen while Jarvis is listening and you are in another app. */
    var listenOrb: Boolean
        get() = sp.getBoolean("listenOrb", true)
        set(v) = sp.edit().putBoolean("listenOrb", v).apply()

    /** Things you told Jarvis to remember. */
    fun facts(): List<String> = str("facts").split('\u001F').filter { it.isNotBlank() }
    fun addFact(f: String) = put("facts", (facts() + f.trim()).takeLast(60).joinToString("\u001F"))
    fun clearFacts() = put("facts", "")

    // ---- study
    var studyLevel: String
        get() = str("studyLevel", "high school")
        set(v) = put("studyLevel", v)

    var focusMinutes: Int
        get() = sp.getInt("focusMinutes", 25)
        set(v) = sp.edit().putInt("focusMinutes", v).apply()

    var focusEnd: Long
        get() = sp.getLong("focusEnd", 0L)
        set(v) = sp.edit().putLong("focusEnd", v).apply()

    fun tasks(): List<Pair<Boolean, String>> =
        str("tasks").split('\u001F').filter { it.length > 2 }.map { (it.startsWith("1|")) to it.substringAfter('|') }

    fun saveTasks(l: List<Pair<Boolean, String>>) =
        put("tasks", l.joinToString("\u001F") { (if (it.first) "1" else "0") + "|" + it.second })

    // ---- notes
    fun notes(): List<String> = str("notes").split('\u001F').filter { it.isNotBlank() }
    fun addNote(n: String) = put("notes", (notes() + n.trim()).joinToString("\u001F"))
    fun clearNotes() = put("notes", "")

    /** Keys are cleaned of spaces, line breaks and quotes: a pasted key often carries an invisible one. */
    fun keyFor(p: String): String = when (p) {
        "groq" -> groqKey
        "claude" -> claudeKey
        else -> geminiKey
    }.filter { !it.isWhitespace() && it != '"' && it != '\'' }

    val activeKey: String get() = keyFor(provider)

    /** True when at least one AI provider has a key. */
    val hasAnyKey: Boolean get() = geminiKey.isNotBlank() || groqKey.isNotBlank() || claudeKey.isNotBlank()
}
