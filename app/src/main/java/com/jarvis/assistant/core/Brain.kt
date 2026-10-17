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
        LocalParser.parse(text) ?: llmPlan(text, history)
    }

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
- flashlight (value = on | off)
- volume (value = up | down | mute)
- settings (value = wifi | bluetooth | airplane | hotspot | display | sound | battery | location)
- note_add (message = the note)
- note_read
- web_answer (target = the question; use for anything needing live or recent facts: sports results, news, prices, "who is the current ...")
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
- answer (no device action; just answer or chat in "reply")
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
    fun webAnswer(question: String): String {
        // news is fetched straight from Google News: faster and always current
        if (Regex("\\b(news|headlines)\\b", RegexOption.IGNORE_CASE).containsMatchIn(question)) {
            val quick = clean(Fallback.answer(question).orEmpty())
            if (quick.isNotBlank()) return quick
        }
        return webAnswerAi(question)
    }

    private fun webAnswerAi(question: String): String = text(
        "You are JARVIS. Today is ${now()}. Answer the question directly with the actual fact (winner, score, number, name) in at most 2 short spoken sentences. " +
            "Use up-to-date web information. Never give links, sources or markdown. If you truly can't find it, say so briefly.",
        question, true
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

    private fun text(system: String, user: String, search: Boolean): String {
        if (!Prefs.hasAnyKey) return (if (search) clean(Fallback.answer(user).orEmpty()) else "").ifBlank { "I need a free Gemini or Groq key for that. You can add it in setup." }
        return try {
            clean(Llm.answer(system, user, search))
        } catch (e: LlmFailure) {
            (if (search) clean(Fallback.answer(user).orEmpty()) else "").ifBlank { friendly(e) }
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
