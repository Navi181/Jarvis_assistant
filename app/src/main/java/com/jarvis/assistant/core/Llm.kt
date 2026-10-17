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
    // Google and Groq retire models often (gemini-flash-latest and Groq's Llama 3.3 both stopped working for free keys),
    // so the live model lists are fetched with your key and the best working model is used. These names are only the backup.
    private val GEMINI_BACKUP = listOf("gemini-3.8-flash", "gemini-3.6-flash", "gemini-2.5-flash", "gemini-3.5-flash-lite", "gemini-2.5-flash-lite")
    private val GROQ_BACKUP = listOf("openai/gpt-oss-120b", "openai/gpt-oss-20b", "qwen/qwen3.8-27b", "llama-3.3-70b-versatile")
    private const val CLAUDE_MODEL = "claude-haiku-5-5"

    @Volatile private var geminiList: List<String> = emptyList()
    @Volatile private var geminiAt = 0L
    @Volatile private var groqList: List<String> = emptyList()
    @Volatile private var groqAt = 0L

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

    // ------------------------------------------------------------------ model discovery

    private val notChat = listOf(
        "tts", "live", "image", "embedding", "audio", "transcribe", "robotics", "omni", "computer", "antigravity",
        "research", "veo", "lyria", "imagen", "banana", "learnlm", "translate", "exp"
    )

    private fun geminiRank(id: String): Double? {
        if (!id.startsWith("gemini-") || notChat.any { it in id }) return null
        val flash = "flash" in id
        val pro = "pro" in id
        if (!flash && !pro) return null
        val ver = Regex("gemini-(\\d+(?:\\.\\d+)?)").find(id)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        var score = ver * 10.0 + when {
            flash && "lite" !in id -> 100.0
            flash -> 60.0
            else -> 40.0
        }
        if ("preview" in id) score -= 30.0
        return score
    }

    private fun geminiModels(key: String): List<String> {
        val now = System.currentTimeMillis()
        val cached = geminiList
        if (cached.isNotEmpty() && now - geminiAt < 3_600_000L) return cached
        val found = try {
            val arr = JSONObject(Net.get("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200", mapOf("x-goog-api-key" to key)))
                .optJSONArray("models") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val m = arr.getJSONObject(i)
                val id = m.optString("name").removePrefix("models/")
                val methods = m.optJSONArray("supportedGenerationMethods")
                val ok = methods != null && (0 until methods.length()).any { methods.optString(it) == "generateContent" }
                val rank = geminiRank(id)
                if (ok && rank != null) id to rank else null
            }.sortedByDescending { it.second }.map { it.first }
        } catch (e: Exception) {
            emptyList()
        }
        val list = (found + GEMINI_BACKUP).distinct()
        geminiList = list
        // if the list couldn't be fetched, look again in 5 minutes instead of an hour
        geminiAt = if (found.isEmpty()) now - 3_300_000L else now
        return list
    }

    private fun groqModels(key: String): List<String> {
        val now = System.currentTimeMillis()
        val cached = groqList
        if (cached.isNotEmpty() && now - groqAt < 3_600_000L) return cached
        val found = try {
            val arr = JSONObject(Net.get("https://api.groq.com/openai/v1/models", mapOf("Authorization" to "Bearer $key")))
                .optJSONArray("data") ?: JSONArray()
            (0 until arr.length()).map { arr.getJSONObject(it).optString("id") }.filter { it.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
        val bad = listOf("whisper", "tts", "guard", "safeguard", "orpheus", "playai", "embed", "compound")
        val chat = found.filter { id -> bad.none { it in id.lowercase() } }
            .sortedBy { id -> GROQ_BACKUP.indexOf(id).let { if (it < 0) 50 else it } }
        val compound = found.filter { "compound" in it }.sortedBy { if (it.endsWith("mini")) 1 else 0 }
        val list = (chat + GROQ_BACKUP).distinct()
        groqList = list
        groqCompound = compound
        groqAt = if (found.isEmpty()) now - 3_300_000L else now
        return list
    }

    @Volatile private var groqCompound: List<String> = emptyList()

    private fun nextModelWorthTrying(e: IOException): Boolean = httpCode(e) in setOf(400, 403, 404, 408, 413, 429, 500, 502, 503, 504) || e is SocketTimeoutException

    // ------------------------------------------------------------------ Gemini

    private fun geminiCall(key: String, body: JSONObject): String {
        val models = geminiModels(key).toMutableList()
        // the model that worked last time goes first
        val good = geminiGood
        if (good != null && good in models) {
            models.remove(good)
            models.add(0, good)
        }
        var last: IOException? = null
        for (model in models.take(5)) {
            try {
                val res = Net.post(
                    "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent",
                    mapOf("x-goog-api-key" to key), body.toString()
                )
                geminiGood = model
                return res
            } catch (e: IOException) {
                last = e
                if (!nextModelWorthTrying(e)) throw e
            }
        }
        // the saved list may be stale: forget it so the next question fetches a fresh one
        geminiAt = 0L
        throw last ?: IOException("no model")
    }

    @Volatile private var geminiGood: String? = null

    private fun geminiText(res: String): String {
        val parts = JSONObject(res).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
        return (0 until parts.length()).map { parts.getJSONObject(it) }
            .filter { !it.optBoolean("thought", false) }
            .joinToString(" ") { it.optString("text", "") }.trim()
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
        return try {
            geminiText(geminiCall(key, body))
        } catch (e: IOException) {
            // if live search itself is refused, still answer from the model's own knowledge
            if (search) geminiAnswer(key, system, user, false) else throw e
        }
    }

    // ------------------------------------------------------------------ Groq

    private fun groqPost(key: String, body: JSONObject): String {
        val res = Net.post("https://api.groq.com/openai/v1/chat/completions", mapOf("Authorization" to "Bearer $key"), body.toString())
        return JSONObject(res).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "").trim()
    }

    /** Tries models in order; moves on when a model is gone, rate-limited or refuses the request. */
    private fun groqTry(key: String, models: List<String>, build: (String, Boolean) -> JSONObject, json: Boolean): String {
        var last: IOException? = null
        for (m in models.take(5)) {
            try {
                return groqPost(key, build(m, json))
            } catch (e: IOException) {
                last = e
                if (httpCode(e) == 400 && json) {
                    // some models don't support JSON mode: ask again without it
                    try {
                        return groqPost(key, build(m, false))
                    } catch (e2: IOException) {
                        last = e2
                    }
                }
                if (!nextModelWorthTrying(last!!)) throw last
            }
        }
        groqAt = 0L
        throw last ?: IOException("no model")
    }

    private fun groq(key: String, system: String, history: List<Msg>, user: String, json: Boolean, temp: Double): String {
        val models = groqModels(key)
        val build = { model: String, useJson: Boolean ->
            val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
            history.forEach {
                messages.put(JSONObject().put("role", if (it.fromUser) "user" else "assistant").put("content", it.text))
            }
            messages.put(JSONObject().put("role", "user").put("content", user))
            val body = JSONObject().put("model", model).put("messages", messages).put("temperature", temp)
            if (useJson) body.put("response_format", JSONObject().put("type", "json_object"))
            body
        }
        return groqTry(key, models, build, json)
    }

    private fun groqAnswer(key: String, system: String, user: String, search: Boolean): String {
        val models = groqModels(key)
        val build = { model: String, _: Boolean ->
            JSONObject().put("model", model).put(
                "messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user))
            )
        }
        if (search) {
            val compound = groqCompound.ifEmpty { listOf("groq/compound", "groq/compound-mini") }
            try {
                val out = groqTry(key, compound, build, false)
                if (out.isNotBlank()) return out
            } catch (e: IOException) {
                // fall through to a plain model
            }
        }
        return groqTry(key, models, build, false)
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
