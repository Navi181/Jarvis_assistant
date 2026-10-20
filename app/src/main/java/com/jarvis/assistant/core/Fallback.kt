package com.jarvis.assistant.core

import org.json.JSONObject
import java.net.URLEncoder

/**
 * Answers that need no AI key at all, used when the AI is unavailable: headlines from Google News,
 * and short facts from DuckDuckGo / Wikipedia. Blocking: call from a background thread.
 */
object Fallback {

    fun looksLikeQuestion(t: String): Boolean {
        val s = t.trim().lowercase()
        return s.endsWith("?") || Regex("^(what|who|whom|whose|when|where|why|how|which|tell me|explain|define|news|latest|is |are |does |do |can |did |was |were )").containsMatchIn(s) ||
            Regex("\\b(news|headlines|score|weather|price|capital of|meaning of|president|prime minister)\\b").containsMatchIn(s)
    }

    /** A short spoken answer, or null if nothing useful was found. */
    fun answer(question: String): String? = try {
        val q = question.trim()
        if (Regex("\\b(news|headlines)\\b", RegexOption.IGNORE_CASE).containsMatchIn(q)) news(q) ?: fact(q)
        else fact(q) ?: news(q)
    } catch (e: Exception) {
        null
    }

    private fun decode(s: String) = s
        .replace("<![CDATA[", "").replace("]]>", "")
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").trim()

    /** Top [n] headlines for a topic (blank = general headlines), straight from Google News. */
    fun headlines(topic: String, n: Int = 2): List<String> = try {
        val url = if (topic.isNotBlank())
            "https://news.google.com/rss/search?q=" + URLEncoder.encode(topic, "UTF-8") + "&hl=en-IN&gl=IN&ceid=IN:en"
        else "https://news.google.com/rss?hl=en-IN&gl=IN&ceid=IN:en"
        Regex("<item>.*?<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).findAll(Net.get(url))
            .map { decode(it.groupValues[1]).substringBeforeLast(" - ") }
            .filter { it.isNotBlank() }.distinct().take(n).toList()
    } catch (e: Exception) { emptyList() }

    private fun news(q: String): String? {
        val topic = q.lowercase()
            .replace(Regex("\\b(what's|what is|whats|the|latest|news|headlines|today|today's|about|on|in|tell me|me|any|is there|are there|please|jarvis|give|show|read)\\b"), " ")
            .replace(Regex("[?.!,]"), " ").replace(Regex("\\s+"), " ").trim()
        val url = if (topic.length >= 3)
            "https://news.google.com/rss/search?q=" + URLEncoder.encode(topic, "UTF-8") + "&hl=en-IN&gl=IN&ceid=IN:en"
        else "https://news.google.com/rss?hl=en-IN&gl=IN&ceid=IN:en"
        val xml = Net.get(url)
        val titles = Regex("<item>.*?<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).findAll(xml)
            .map { decode(it.groupValues[1]).substringBeforeLast(" - ") }
            .filter { it.isNotBlank() }.distinct().take(3).toList()
        if (titles.isEmpty()) return null
        return (if (topic.length >= 3) "Latest on $topic. " else "Top headlines. ") + titles.joinToString(". ") + "."
    }

    private fun fact(q: String): String? {
        val clean = q.replace(Regex("(?i)^(jarvis[, ]*)?(tell me |can you tell me |do you know )?"), "").trim().trimEnd('?', '.', '!')
        val enc = URLEncoder.encode(clean, "UTF-8")
        try {
            val d = JSONObject(Net.get("https://api.duckduckgo.com/?format=json&no_html=1&skip_disambig=1&q=$enc"))
            val t = d.optString("AbstractText").ifBlank { d.optString("Answer") }
            if (t.isNotBlank()) return firstSentences(t)
        } catch (_: Exception) {
        }
        val subject = clean.replace(Regex("(?i)^(what|who|where|when|how|which)\\s+(is|are|was|were|does|do|did)?\\s*(the|a|an)?\\s*"), "")
        val s = JSONObject(Net.get("https://en.wikipedia.org/w/api.php?action=query&list=search&format=json&srlimit=1&srsearch=" + URLEncoder.encode(subject.ifBlank { clean }, "UTF-8")))
            .optJSONObject("query")?.optJSONArray("search")?.optJSONObject(0)?.optString("title").orEmpty()
        if (s.isBlank()) return null
        val sum = JSONObject(Net.get("https://en.wikipedia.org/api/rest_v1/page/summary/" + URLEncoder.encode(s, "UTF-8").replace("+", "%20")))
            .optString("extract")
        return if (sum.isBlank()) null else firstSentences(sum)
    }

    private fun firstSentences(t: String): String {
        val parts = t.replace(Regex("\\s*\\([^)]*\\)"), "").split(Regex("(?<=[.!?])\\s+"))
        val out = StringBuilder()
        for (p in parts) {
            if (out.length + p.length > 280 && out.isNotEmpty()) break
            out.append(p).append(' ')
            if (out.length > 120) break
        }
        return out.toString().trim()
    }
}
