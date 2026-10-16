package com.jarvis.assistant.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException

/** Why an AI call failed, in terms the user can act on. */
class LlmFailure(val kind: Kind, val provider: String, val code: Int = 0) : Exception("$kind $provider $code") {
    enum class Kind { NO_KEY, QUOTA, BAD_KEY, NETWORK, SERVER, OTHER }

    val providerName: String
        get() = when (provider) {
            "groq" -> "Groq"
            "claude" -> "Claude"
            else -> "Gemini"
        }
}

/**
 * One place that talks to the AI providers (Gemini, Groq, Claude).
 * The provider chosen in Setup is tried first; any other provider that has a key is a backup,
 * so a used-up free quota on one does not stop Jarvis. Blocking: call from a background thread.
 */
object Llm {
    // The "-latest" names are aliases, so they keep working when Google retires specific versions.
    // Each model has its own free quota, so falling through the list also helps with limits.
    private val GEMINI_MODELS = listOf("gemini-flash-latest", "gemini-flash-lite-latest", "gemini-2.5-flash")
    private const val GROQ_MODEL = "llama-3.3-70b-versatile"
    private const val CLAUDE_MODEL = "claude-haiku-5-5"

    /** Last failure, for the Setup screen. */
    @Volatile
    var lastProblem: String = ""

    /** JSON or text chat with history (used to decide what to do). */
    fun chat(system: String, history: List<Msg>, user: String, json: Boolean, temperature: Double = 0.4): String =
        attempt(search = false) { p, key ->
            when (p) {
                "groq" -> groq(key, system, history, user, json, temperature)
                "claude" -> claude(key, system, history, user, if (json) 300 else 500)
                else -> gemini(key, system, history, user, json, temperature)
            }
        }

    /** Plain-text answer. With [search] the provider's live web search is used (Gemini Google Search / Groq compound). */
    fun answer(system: String, user: String, search: Boolean): String =
        attempt(search) { p, key ->
            when (p) {
                "groq" -> groqAnswer(key, system, user, search)
                "claude" -> claude(key, system, emptyList(), user, 700)
                else -> geminiAnswer(key, system, user, search)
            }
        }

    // ------------------------------------------------------------------ provider order and error handling

    private fun order(search: Boolean): List<String> {
        val all = listOf(Prefs.provider) + listOf("gemini", "groq", "claude").filter { it != Prefs.provider }
        val withKey = all.filter { Prefs.keyFor(it).isNotBlank() }
        if (!search) return withKey
        // Claude has no live search here, so prefer the others for live questions.
        val live = withKey.filter { it != "claude" }
        return if (live.isEmpty()) withKey else live
    }

    private fun attempt(search: Boolean, call: (String, String) -> String): String {
        val list = order(search)
        if (list.isEmpty()) throw LlmFailure(LlmFailure.Kind.NO_KEY, Prefs.provider)
        var first: LlmFailure? = null
        for (p in list) {
            try {
                val out = call(p, Prefs.keyFor(p))
                if (out.isNotBlank()) {
                    lastProblem = ""
                    return out
                }
                if (first == null) first = LlmFailure(LlmFailure.Kind.OTHER, p)
            } catch (e: LlmFailure) {
                if (first == null) first = e
            } catch (e: IOException) {
                if (first == null) first = classify(p, e)
            } catch (e: Exception) {
                if (first == null) first = LlmFailure(LlmFailure.Kind.OTHER, p)
            }
        }
        val f = first ?: LlmFailure(LlmFailure.Kind.OTHER, list.first())
        lastProblem = "${f.providerName}: ${f.kind}" + if (f.code != 0) " (HTTP ${f.code})" else ""
        throw f
    }

    private fun httpCode(e: IOException): Int =
        Regex("HTTP (\\d{3})").find(e.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun classify(provider: String, e: IOException): LlmFailure {
        val code = httpCode(e)
        val kind = when {
            code == 429 -> LlmFailure.Kind.QUOTA
            code == 401 || code == 403 -> LlmFailure.Kind.BAD_KEY
            code == 400 && "API key" in e.message.orEmpty() -> LlmFailure.Kind.BAD_KEY
            code >= 500 -> LlmFailure.Kind.SERVER
            code == 0 -> LlmFailure.Kind.NETWORK
            else -> LlmFailure.Kind.OTHER
        }
        return LlmFailure(kind, provider, code)
    }

    /** Errors where trying another Gemini model can help. */
    private fun worthNextModel(e: IOException): Boolean = httpCode(e) in setOf(404, 429, 500, 502, 503, 504) || e is SocketTimeoutException

    // ------------------------------------------------------------------ Gemini

    private fun geminiCall(key: String, body: JSONObject): String {
        var last: IOException? = null
        for (model in GEMINI_MODELS) {
            try {
                return Net.post(
                    "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent",
                    mapOf("x-goog-api-key" to key), body.toString()
                )
            } catch (e: IOException) {
                last = e
                if (!worthNextModel(e)) throw e
            }
        }
        throw last ?: IOException("no model")
    }

    private fun geminiText(res: String): String {
        val parts = JSONObject(res).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
        return (0 until parts.length()).joinToString(" ") { parts.getJSONObject(it).optString("text", "") }.trim()
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
        return geminiText(geminiCall(key, body))
    }

    private fun geminiAnswer(key: String, system: String, user: String, search: Boolean): String {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", user)))))
        if (search) body.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        return geminiText(geminiCall(key, body))
    }

    // ------------------------------------------------------------------ Groq

    private fun groqPost(key: String, body: JSONObject): String {
        val res = Net.post("https://api.groq.com/openai/v1/chat/completions", mapOf("Authorization" to "Bearer $key"), body.toString())
        return JSONObject(res).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
    }

    private fun groq(key: String, system: String, history: List<Msg>, user: String, json: Boolean, temp: Double): String {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        history.forEach {
            messages.put(JSONObject().put("role", if (it.fromUser) "user" else "assistant").put("content", it.text))
        }
        messages.put(JSONObject().put("role", "user").put("content", user))
        val body = JSONObject().put("model", GROQ_MODEL).put("messages", messages).put("temperature", temp)
        if (json) body.put("response_format", JSONObject().put("type", "json_object"))
        return groqPost(key, body)
    }

    private fun groqAnswer(key: String, system: String, user: String, search: Boolean): String {
        val body = JSONObject()
            .put("model", if (search) "groq/compound" else GROQ_MODEL)
            .put(
                "messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user))
            )
        return groqPost(key, body)
    }

    // ------------------------------------------------------------------ Claude (needs a paid Anthropic API key)

    private fun claude(key: String, system: String, history: List<Msg>, user: String, maxTokens: Int): String {
        // Claude wants messages that start with the user and alternate, so tidy the history first.
        val turns = ArrayList<Pair<String, String>>()
        (history.map { (if (it.fromUser) "user" else "assistant") to it.text } + ("user" to user)).forEach { (role, text) ->
            if (turns.isNotEmpty() && turns.last().first == role) turns[turns.size - 1] = role to (turns.last().second + "\n" + text)
            else if (turns.isNotEmpty() || role == "user") turns.add(role to text)
        }
        val messages = JSONArray()
        turns.forEach { messages.put(JSONObject().put("role", it.first).put("content", it.second)) }
        val body = JSONObject()
            .put("model", CLAUDE_MODEL)
            .put("max_tokens", maxTokens)
            .put("system", system)
            .put("messages", messages)
        val res = Net.post(
            "https://api.anthropic.com/v1/messages",
            mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"),
            body.toString()
        )
        val content = JSONObject(res).getJSONArray("content")
        return (0 until content.length()).joinToString(" ") { content.getJSONObject(it).optString("text", "") }.trim()
    }
}
