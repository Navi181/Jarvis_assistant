package com.jarvis.assistant.core

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * The "Jarvis" voice: Gemini's speech models speak the reply in a deep, calm British male voice.
 * Uses the same free Gemini key as the brain. If it is unavailable (no key, offline, quota used up) the phone's own voice speaks instead.
 */
object GeminiTts {
    /** Deep male Gemini voices, best first. "Next voice" cycles through them. */
    val voices = listOf("Charon", "Orus", "Algenib", "Iapetus", "Alnilam", "Rasalgethi")

    private const val BACKUP_MODEL = "gemini-2.5-flash-preview-tts"
    private var models: List<String> = emptyList()
    private var modelsAt = 0L

    @Volatile
    private var blockedUntil = 0L

    fun available(): Boolean = Extras.aiVoice && Prefs.geminiKey.isNotBlank() && System.currentTimeMillis() > blockedUntil

    private fun speechModels(key: String): List<String> {
        val now = System.currentTimeMillis()
        if (models.isNotEmpty() && now - modelsAt < 6 * 3600_000L) return models
        val found = try {
            val arr = JSONObject(
                Net.get("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200", mapOf("x-goog-api-key" to key))
            ).optJSONArray("models") ?: JSONArray()
            (0 until arr.length()).map { arr.getJSONObject(it) }
                .filter { it.optString("name").contains("tts") }
                .filter { m ->
                    val methods = m.optJSONArray("supportedGenerationMethods")
                    methods == null || (0 until methods.length()).any { methods.optString(it) == "generateContent" }
                }
                .map { it.optString("name").removePrefix("models/") }
                .let { list -> list.filter { "pro" !in it }.sortedDescending() + list.filter { "pro" in it }.sortedDescending() }
        } catch (e: Exception) {
            emptyList()
        }
        models = (found + BACKUP_MODEL).distinct()
        modelsAt = now
        return models
    }

    /** 16-bit mono PCM and its sample rate, or null when this reply must use the phone's own voice. */
    fun synth(text: String): Pair<ByteArray, Int>? {
        if (!available()) return null
        val key = Prefs.keyFor("gemini")
        val voice = Extras.aiVoiceName.ifBlank { voices[0] }
        // no style instruction here: the speech model reads such text out loud. The deep voice comes from the chosen voice itself.
        val prompt = text
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put(
                "generationConfig", JSONObject()
                    .put("responseModalities", JSONArray().put("AUDIO"))
                    .put(
                        "speechConfig", JSONObject().put(
                            "voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice))
                        )
                    )
            )
            .toString()
        for (m in speechModels(key).take(3)) {
            try {
                val res = JSONObject(
                    Net.post("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent", mapOf("x-goog-api-key" to key), body)
                )
                val part = res.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0)
                val inline = part.getJSONObject("inlineData")
                val rate = Regex("rate=(\\d+)").find(inline.optString("mimeType"))?.groupValues?.get(1)?.toIntOrNull() ?: 24000
                val pcm = Base64.decode(inline.getString("data"), Base64.DEFAULT)
                if (pcm.size > 1000) return pcm to rate
            } catch (e: IOException) {
                val msg = e.message.orEmpty()
                if ("HTTP 429" in msg) {
                    blockedUntil = System.currentTimeMillis() + 90_000L
                    return null
                }
                if ("HTTP 404" in msg || "HTTP 400" in msg || "HTTP 403" in msg) continue
                return null // no connection or a server hiccup: the phone voice covers this one
            } catch (e: Exception) {
                continue
            }
        }
        blockedUntil = System.currentTimeMillis() + 300_000L
        return null
    }
}
