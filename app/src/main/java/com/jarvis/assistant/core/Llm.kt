package com.jarvis.assistant.core

import org.json.JSONArray
import org.json.JSONObject

/** One place that talks to the free AI providers (Gemini or Groq). Blocking: call from a background thread. */
object Llm {
    // "gemini-flash-latest" is an alias, so this keeps working when Google retires specific versions.
    private const val GEMINI_MODEL = "gemini-flash-latest"
    private const val GROQ_MODEL = "llama-3.3-70b-versatile"

    /** JSON or text chat with history (used to decide what to do). */
    fun chat(system: String, history: List<Msg>, user: String, json: Boolean, temperature: Double = 0.4): String {
        val key = Prefs.activeKey
        return if (Prefs.provider == "groq") groq(key, system, history, user, json, temperature)
        else gemini(key, system, history, user, json, temperature)
    }

    /** Plain-text answer. With [search] the provider's live web search is used (Gemini Google Search / Groq compound). */
    fun answer(system: String, user: String, search: Boolean): String {
        val key = Prefs.activeKey
        if (Prefs.provider == "groq") {
            val body = JSONObject()
                .put("model", if (search) "groq/compound" else GROQ_MODEL)
                .put(
                    "messages", JSONArray()
                        .put(JSONObject().put("role", "system").put("content", system))
                        .put(JSONObject().put("role", "user").put("content", user))
                )
            val res = Net.post("https://api.groq.com/openai/v1/chat/completions", mapOf("Authorization" to "Bearer $key"), body.toString())
            return JSONObject(res).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        }
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", user)))))
        if (search) body.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        val res = Net.post(
            "https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent",
            mapOf("x-goog-api-key" to key), body.toString()
        )
        val parts = JSONObject(res).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
        return (0 until parts.length()).joinToString(" ") { parts.getJSONObject(it).optString("text", "") }
    }

    private fun gemini(key: String, system: String, history: List<Msg>, user: String, json: Boolean, temp: Double): String {
        val contents = JSONArray()
        history.forEach {
            contents.put(
                JSONObject().put("role", if (it.fromUser) "user" else "model")
                    .put("parts", JSONArray().put(JSONObject().put("text", it.text)))
            )
        }
        contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", user))))
        val gen = JSONObject().put("temperature", temp)
        if (json) gen.put("responseMimeType", "application/json")
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)
            .put("generationConfig", gen)
        val res = Net.post(
            "https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent",
            mapOf("x-goog-api-key" to key), body.toString()
        )
        return JSONObject(res).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
    }

    private fun groq(key: String, system: String, history: List<Msg>, user: String, json: Boolean, temp: Double): String {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        history.forEach {
            messages.put(JSONObject().put("role", if (it.fromUser) "user" else "assistant").put("content", it.text))
        }
        messages.put(JSONObject().put("role", "user").put("content", user))
        val body = JSONObject().put("model", GROQ_MODEL).put("messages", messages).put("temperature", temp)
        if (json) body.put("response_format", JSONObject().put("type", "json_object"))
        val res = Net.post("https://api.groq.com/openai/v1/chat/completions", mapOf("Authorization" to "Bearer $key"), body.toString())
        return JSONObject(res).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
    }
}
