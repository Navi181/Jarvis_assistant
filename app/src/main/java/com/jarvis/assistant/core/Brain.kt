package com.jarvis.assistant.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/**
 * Understanding: instant offline rules first ([LocalParser]), then a free LLM (Gemini or Groq)
 * for everything else: natural phrasing, questions, chit-chat, multi-step requests.
 */
object Brain {

    suspend fun plan(text: String, history: List<Msg>): Plan = withContext(Dispatchers.IO) {
        // plain questions skip the "work out what to do" step and go straight to one live answer: half the waiting
        LocalParser.parse(text) ?: (if (isFactQuestion(text) && Prefs.hasAnyKey) Plan("web_answer", target = text) else llmPlan(text, history))
    }

    private val deviceWords = Regex(
        "(?i)\\b(call|text|message|whatsapp|sms|email|alarm|timer|remind|reminder|open|launch|play|pause|volume|brightness|wifi|wi-fi|bluetooth|" +
            "flashlight|torch|calendar|schedule|meeting|navigate|directions|weather|temperature|forecast|screenshot|battery|settings|note|photo|picture|" +
            "camera|install|download|buy|order|shop|turn|switch|set|add|send|remember|forget|quiz|flashcards?|chapter|unit|lesson|my|screen|phone|music|song)\\b"
    )
    private val askStart = Regex("(?i)^(who|what|when|where|why|how|which|whose|is|are|was|were|does|do|did|can|could|tell me|explain|define|meaning of|give me)\\b")

    fun isFactQuestion(t: String): Boolean =
        t.trim().split(Regex("\\s+")).size >= 3 && askStart.containsMatchIn(t.trim()) && !deviceWords.containsMatchIn(t)

    private fun personalityLine() = when (Prefs.personality) {
        "formal" -> "You are formal, precise and respectful, like a butler."
        "friendly" -> "You are warm, friendly and encouraging."
        "sarcastic" -> "You are dry and sarcastic in a light, affectionate way, but always helpful."
        else -> "You are calm, witty and quietly confident, like Tony Stark's JARVIS."
    }

    private fun lengthLine() = when (Prefs.answerLen) {
        "detailed" -> "up to 6 spoken sentences when the question deserves it"
        "normal" -> "at most 4 short sentences"
        else -> "at most 2 short sentences"
    }

    private fun langName() = when (Prefs.language.substringBefore('-')) {
        "hi" -> "Hindi"
        "kn" -> "Kannada"
        "ta" -> "Tamil"
        "te" -> "Telugu"
        "ml" -> "Malayalam"
        "mr" -> "Marathi"
        "bn" -> "Bengali"
        "es" -> "Spanish"
        "fr" -> "French"
        "de" -> "German"
        else -> "English"
    }

    private fun factsBlock(): String {
        val f = Prefs.facts()
        return if (f.isEmpty()) "" else "Things the user told you to remember: " + f.joinToString("; ") + "."
    }

    private fun now(): String = DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.SHORT).format(Date())

    private fun systemPrompt(): String = """
You are JARVIS, a highly capable voice assistant living on the user's Android phone. ${personalityLine()} Address the user as "${Prefs.callMe}" occasionally, not every time.
Your replies are spoken aloud: ${lengthLine()}, plain text, no markdown, no emojis, no lists. Reply in ${langName()} unless the user speaks another language.
${factsBlock()}
Respond with ONLY a JSON object with exactly these keys: action, target, message, hour, minute, seconds, value, reply.
action must be one of:
- call (target = contact name or phone number)
- sms (target = contact, message = text; value = "whatsapp" if they asked for WhatsApp; value = "whatsapp_group" and target = the group name if they want to message a WhatsApp group)
- alarm (hour 0-23, minute 0-59, message = optional label)
- timer (seconds)
- reminder (message = what to remind; either seconds for "in 10 minutes" with value "timer", or hour and minute with value "alarm")
- open_app (target = app name)
- play_music (target = song / artist / playlist; value = "spotify" or "amazon" or "joytify" or "youtube" if the user named one, else "")
- media (value = pause | play | next | prev | stop)
- navigate (target = place)
- maps (target = what to find on the map, e.g. "pharmacy near me")
- search (target = web search query)
- weather (target = city, or "" for the user's home city)
- global (value = back | home | recents | notifications | quick_settings | screenshot | lock | power | split; needs Accessibility)
- volume_set (value = 0-100, target = media | ring | alarm | notification)
- ringer (value = silent | vibrate | normal)
- brightness (value = up | down | 0-100)
- flashlight (value = on | off)
- volume (value = up | down | mute)
- settings (opens a settings page: value = wifi | bluetooth | airplane | hotspot | display | sound | battery | location | nfc | storage | date | language | accessibility | privacy | security | data usage | vpn | cast | developer | default apps | keyboard | about | sync | apps | notifications | wallpaper)
- note_add (message = the note)
- note_read
- web_answer (target = the question; use for ANY factual or knowledge question: science, history, people, places, sport, news, prices, health, law, how-to, definitions, "who is ...", "what is ...", "why ...". target must be a complete standalone question: replace words like "it", "that", "he" using the conversation so far)
- elaborate (target = what to go into depth on, or "" for your previous answer; use when the user says elaborate, in detail, tell me more, explain fully, full story, deep dive, or asks about one part of an earlier answer)
- toggle (target = wifi | bluetooth | hotspot | etc, value = on | off)
- phone_do (target = the whole task in plain words; use for ANY job on the phone that no other action covers, in any app: toggling a setting, ordering, replying inside an app, checking something in an app. You will look at the screen and tap through it)
- shop (target = item, value = amazon | flipkart; opens the store search)
- install_app (target = app name; opens the Play Store)
- calendar (message = event title, hour and minute = start, value = today | tomorrow | yyyy-MM-dd, seconds = length in seconds; opens the calendar prefilled)
- remember (message = the fact to remember about the user)
- screen_read (target = what the user wants to know about the current screen, or "")
- agenda (value = today | tomorrow; reads the calendar aloud)
- tutor (value = explain | quiz | flashcards | summary; target = topic)
- photo (target = the user's own words describing which photo to open, e.g. "sunset in goa last week")
- update (check for and install a new version of this app)
- camera (value = "selfie" or "")
- notifications (read the user's notifications aloud)
- answer (ONLY for greetings, chit-chat, opinions and questions you must ask the user; never for facts)
Unused keys: "" for text, -1 for hour, 0 for minute and seconds.
"reply": for device actions, a short confirmation in your own voice. For "answer", the actual answer.
If a needed detail is missing (who to call, what time), use action "answer" and ask one short question in "reply".
Current date and time: ${now()}
""".trimIndent()

    /** Plain-words reason an AI call failed, with what to do about it. */
    internal fun friendly(f: LlmFailure): String = when (f.kind) {
        LlmFailure.Kind.NO_KEY -> "I need an AI key for that. Add one in setup. Gemini and Groq are free."
        LlmFailure.Kind.QUOTA -> "${f.providerName}'s free quota is used up for now. Add a second key in setup and I'll switch to it automatically, or try again in a few minutes."
        LlmFailure.Kind.BAD_KEY -> "${f.providerName} didn't accept my key. Please check it in setup."
        LlmFailure.Kind.NETWORK -> "I can't reach the internet right now."
        LlmFailure.Kind.SERVER -> "${f.providerName} is busy at the moment. Please try again in a moment."
        LlmFailure.Kind.OTHER -> "${f.providerName} gave me an answer I couldn't use" + (if (f.code != 0) " (code ${f.code})" else "") + ". Please try again."
    }

    private fun llmPlan(text: String, history: List<Msg>): Plan {
        if (!Prefs.hasAnyKey) {
            val free = if (Fallback.looksLikeQuestion(text)) clean(Fallback.answer(text).orEmpty()) else ""
            return Plan(
                "answer",
                reply = free.ifBlank { "I can do calls, texts, alarms, reminders, notes and maths offline. For anything else I need a free Gemini or Groq key. You can add it in setup." }
            )
        }
        return try {
            parse(Llm.chat(systemPrompt(), history, text, json = true))
        } catch (e: LlmFailure) {
            // the AI is unavailable: still try to answer questions straight from the web
            val free = if (Fallback.looksLikeQuestion(text)) clean(Fallback.answer(text).orEmpty()) else ""
            Plan("answer", reply = free.ifBlank { friendly(e) })
        } catch (e: Exception) {
            Plan("answer", reply = "Sorry, I got confused by that one. Could you say it again?")
        }
    }

    internal fun parse(raw: String): Plan {
        // Some providers wrap the JSON in extra words or code fences, so take just the braces.
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        val cleaned = if (start in 0 until end) raw.substring(start, end + 1) else raw.trim()
        val o = JSONObject(cleaned)
        return Plan(
            action = o.optString("action", "answer"),
            target = o.optString("target", ""),
            message = o.optString("message", ""),
            hour = o.optInt("hour", -1),
            minute = o.optInt("minute", 0),
            seconds = o.optInt("seconds", 0),
            value = o.optString("value", ""),
            reply = o.optString("reply", "")
        )
    }

    // ------------------------------------------------------------------ direct answers & tutoring (plain text)

    /** Live facts answered directly (Gemini with Google Search, or Groq's built-in web search). No links. */
    fun wantsDetail(q: String): Boolean = Regex(
        "(?i)\\b(in detail|detailed|in depth|in full|elaborate|more details?|full details?|full (?:story|news|report)|tell me more|go deeper|deep dive|at length|explain (?:it )?(?:more|further|properly|fully)|everything about)\\b"
    ).containsMatchIn(q)

    private fun recentContext(): String = Assistant.messages.value.takeLast(8)
        .joinToString("\n") { (if (it.fromUser) "User: " else "Jarvis: ") + it.text.take(700) }

    /** A full, spoken-style explanation (about 350 words), grounded in live web results. Remembers the conversation. */
    fun elaborate(topic: String): String {
        val ask = topic.ifBlank { "Tell me more about your last answer, in detail." }
        if (!Prefs.hasAnyKey) {
            return clean(Fallback.answer(ask).orEmpty()).ifBlank { "For long answers I need a free Gemini or Groq key. You can add it in setup." }
        }
        return text(
            "You are JARVIS. Today is ${now()}. The user wants a thorough, well explained answer. Use up-to-date web information. " +
                "Cover the whole subject in order, with the key facts, numbers, names and dates, in about 300 to 400 words of short spoken paragraphs. " +
                "Plain text only: no markdown, no bullet symbols, no links, no sources. " +
                "If the request says it, that, or the news, work out the subject from the conversation below. " +
                "Reply in ${langName()}.",
            "Conversation so far:\n${recentContext()}\n\nRequest: $ask", true, ask
        )
    }

    fun webAnswer(question: String): String {
        if (wantsDetail(question)) return elaborate(question)
        // news is fetched straight from Google News: faster and always current
        if (Regex("\\b(news|headlines)\\b", RegexOption.IGNORE_CASE).containsMatchIn(question)) {
            val quick = clean(Fallback.answer(question).orEmpty())
            if (quick.isNotBlank()) return quick
        }
        return webAnswerAi(question)
    }

    private fun webAnswerAi(question: String): String = text(
        "You are JARVIS. Today is ${now()}. Answer the question directly, starting with the actual answer (winner, score, number, name, explanation), in ${lengthLine()}. " +
                "Use up-to-date web information for anything that can change; for settled knowledge answer fully and correctly. Never give links, sources or markdown. " +
                "Say the user can ask you to elaborate if there is more. If you truly can't find it, say so briefly.",
        "Conversation so far:\n${recentContext()}\n\nQuestion: $question", true, question
    )

    fun screenSummary(pkg: String, text: String): String = text(
        "You are JARVIS. The user asked what is on their phone screen. Describe it in at most 3 short spoken sentences. Plain text, no markdown.",
        "App: $pkg\nVisible text:\n$text", false
    )

    fun tutor(kind: String, topic: String, spoken: Boolean): String {
        val chapter = Regex("(?i)\\b(chapter|unit|lesson|topic|syllabus|textbook|ncert|icse|cbse|class \\d+|grade \\d+)\\b").containsMatchIn(topic)
        val style = when {
            spoken && chapter -> "Reply in at most 6 short spoken sentences, covering the main ideas in order."
            spoken -> "Reply in at most 3 short spoken sentences."
            chapter -> "Teach it properly: start with a 2 line overview, then the key ideas as short numbered points with one simple example each, then 3 quick revision questions. Under 450 words."
            else -> "Be clear and well structured but concise, under 220 words."
        }
        val task = when (kind) {
            "quiz" -> if (spoken) "Ask me ONE quiz question about" else "Write a 5 question multiple-choice quiz (options A to D, answers at the end) about"
            "flashcards" -> "Write 6 flashcards (lines starting Q: and A:) about"
            "summary" -> "Summarise"
            else -> "Explain"
        }
        val subject = topic.ifBlank { "a topic of your choice from my studies" }
        return text(
            "You are a patient, encouraging tutor for a ${Prefs.studyLevel} student, expert in school and college syllabuses (NCERT, CBSE, ICSE, state boards, IB, university). " +
                "When a chapter, book or exam is named, look it up and teach what that syllabus actually covers. Plain text only: no markdown, no asterisks, no links. $style",
            "$task: $subject", true
        )
    }

    /** Answers a question about whatever is on the phone's screen right now. */
    fun describeScreen(screen: String, ask: String): String = text(
        "You are JARVIS reading the user's phone screen. The screen content is a list of items. Answer the user's request about it in at most 4 short spoken sentences, plain text.",
        "Request: ${ask.ifBlank { "Tell me what is on my screen and summarise it." }}\nScreen:\n$screen", false
    )

    private fun text(system: String, user: String, search: Boolean, fb: String = user): String {
        if (!Prefs.hasAnyKey) return (if (search) clean(Fallback.answer(fb).orEmpty()) else "").ifBlank { "I need a free Gemini or Groq key for that. You can add it in setup." }
        return try {
            clean(Llm.answer(system, user, search))
        } catch (e: LlmFailure) {
            (if (search) clean(Fallback.answer(fb).orEmpty()) else "").ifBlank { friendly(e) }
        } catch (e: Exception) {
            "Sorry, I couldn't work that out."
        }
    }

    private fun clean(s: String): String = s
        .replace(Regex("\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
        .replace(Regex("https?://\\S+"), "")
        .replace(Regex("[*#`_]{1,3}"), "")
        .replace(Regex("[ \\t]+"), " ").trim()
}
