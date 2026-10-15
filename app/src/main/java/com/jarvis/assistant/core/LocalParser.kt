package com.jarvis.assistant.core

import java.time.LocalTime
import kotlin.math.roundToInt

/**
 * Offline understanding of spoken commands: instant, free, works without internet.
 * Anything it can't parse returns null and is handed to the AI model instead.
 * Pure Kotlin (no Android classes) so it is covered by unit tests on every build.
 */
object LocalParser {

    fun nowMinutes(): Int = LocalTime.now().let { it.hour * 60 + it.minute }

    private val lead = Regex(
        "^(?:(?:hey|hi|ok|okay|yo)\\s+)?(?:jarvis[,]?\\s*)?(?:(?:can|could|will|would) you\\s+)?(?:please\\s+)?(?:go ahead and\\s+)?"
    )

    fun parse(raw: String, now: Int = nowMinutes()): Plan? {
        val lower = raw.trim().lowercase().replace(Regex("\\b([ap])\\.\\s?m\\b\\.?"), "$1m")
        val t = lead.replace(lower, "").trim().trimEnd('.', '!', '?', ',', ' ')
        if (t.isEmpty()) return if (lower.contains("jarvis")) Plan("greet") else null

        // ---- conversation basics
        media(t)?.let { return it }
        if (Regex("^(?:stop|cancel|never ?mind|forget it|quiet|be quiet|shut up|that'?s all|nothing|no thanks?|that'?s it)$").matches(t)) return Plan("noop")
        if (Regex("^(?:thanks|thank you|thank you so much|thanks a lot|cheers|good job|well done)\\b.*$").matches(t)) return Plan("thanks")
        if (Regex("^(?:hello|hi|hey|yo|are you there|you there|jarvis)$").matches(t)) return Plan("greet")
        if (Regex("^(?:who are you|what are you|what(?:'s| is) your name)$").matches(t)) return Plan("about")
        if (Regex("^(?:help|what can you do|what do you do|what are your (?:skills|features)|show (?:me )?(?:your )?skills)$").matches(t)) return Plan("help")
        if (Regex("\\b(?:good morning|morning briefing|daily briefing|brief me|my briefing|what(?:'s| is) my day)\\b").containsMatchIn(t)) return Plan("briefing")

        // ---- flashlight
        if (Regex("\\b(flash ?light|torch)\\b").containsMatchIn(t)) {
            val v = when {
                Regex("\\b(off|disable|kill)\\b").containsMatchIn(t) -> "off"
                Regex("\\b(on|enable)\\b").containsMatchIn(t) -> "on"
                else -> "toggle"
            }
            return Plan("flashlight", value = v)
        }

        // ---- timer / reminder / alarm
        if (t.contains("timer") && !Regex("\\b(cancel|stop|delete)\\b").containsMatchIn(t)) {
            val secs = parseDuration(t) ?: return Plan("timer")
            return Plan("timer", seconds = secs)
        }
        Regex("^(?:set|create|add|make)(?: a| an)? reminder(?: to| for| that| about)?\\s+(.+)$").find(t)?.let {
            return reminder(it.groupValues[1], now)
        }
        Regex("^remind me\\s*(.*)$").find(t)?.let { return reminder(it.groupValues[1], now) }
        if (t.contains("alarm") || t.contains("wake me")) {
            if (Regex("\\b(cancel|delete|remove|turn off|switch off|disable|stop)\\b").containsMatchIn(t)) return Plan("alarm_cancel")
            if (Regex("\\bin\\s+(?:\\d|an?\\s|half)").containsMatchIn(Num.digitize(t))) {
                parseDuration(t)?.let { return Plan("timer", seconds = it, message = "alarm") }
            }
            val c = parseClock(t, now) ?: return Plan("alarm")
            return Plan("alarm", hour = c.first, minute = c.second)
        }

        // ---- notes
        Regex("^(?:take (?:a )?note|make (?:a )?note|note down|write down|add (?:a )?note|jot down|note)(?:\\s+(?:that|of|saying|to self))?\\s+(.+)$")
            .find(t)?.let { return Plan("note_add", message = it.groupValues[1].trim()) }
        if (Regex("^(?:read|show|list|play)(?: me)?(?: all)?(?: my)? notes$|^what(?: are|'s| is)(?: in)? my notes$|^do i have any notes$").matches(t)) return Plan("note_read")
        if (Regex("^(?:clear|delete|erase|remove)(?: all)?(?: my)? notes$").matches(t)) return Plan("note_clear")

        // ---- weather
        if (Regex("\\b(weather|forecast|temperature outside|will it rain|is it raining|how (?:hot|cold|warm) is it)\\b").containsMatchIn(t)) {
            var city = Regex("\\b(?:in|for|at|of)\\s+([a-z][a-z .'-]*?)(?:\\s+(?:today|tonight|tomorrow|now|right now|this (?:morning|evening|afternoon)))?$")
                .find(t)?.groupValues?.get(1)?.trim().orEmpty()
            if (city in setOf("today", "tonight", "tomorrow", "now", "here", "me", "outside", "my area", "my location", "the morning", "the evening")) city = ""
            return Plan("weather", target = city)
        }

        // ---- photos: must come before "live facts" ("show me yesterday's photos" is not news)
        PhotoQuery.parse(t)?.let { return Plan("photo", target = t) }
        if (Regex("^(?:update (?:yourself|jarvis|the app)|check for updates?|are you up to date|install (?:the )?(?:latest )?update|upgrade yourself)$").matches(t)) return Plan("update")

        // ---- study helper
        Regex("^(?:quiz me|test me)(?: on| about)?\\s*(.*)$").find(t)?.let { return Plan("tutor", target = it.groupValues[1].trim(), value = "quiz") }
        Regex("^(?:make |create |give me )?flash ?cards?(?: for| on| about)?\\s+(.+)$").find(t)?.let { return Plan("tutor", target = it.groupValues[1].trim(), value = "flashcards") }
        Regex("^(?:summarize|summarise|summary of)\\s+(.+)$").find(t)?.let { return Plan("tutor", target = it.groupValues[1].trim(), value = "summary") }
        Regex("^(?:explain|teach me|help me understand)\\s+(.+)$").find(t)?.let { return Plan("tutor", target = it.groupValues[1].trim(), value = "explain") }

        // ---- live facts: answered directly from the web, never as links
        if (Regex("\\b(who won|who is winning|who'?s winning|who wins|live score|scores? (?:of|for)|latest news|news (?:about|on|today)|breaking news|stock price|share price|price of|exchange rate|who is the current|current (?:president|prime minister|ceo|champion)|when is the next|yesterday'?s|last night'?s|today'?s (?:match|game))\\b").containsMatchIn(t)) {
            return Plan("web_answer", target = t)
        }

        // ---- messages / calls (before maths so phone numbers aren't "calculated")
        Regex("^(?:whatsapp|send a whatsapp(?: message)? to)\\s+(.+)$").find(t)?.let { return smsPlan(it.groupValues[1], true) }
        if (Regex("^(?:text|sms|message)$").matches(t)) return Plan("sms")
        Regex("^(?:text|sms|message|send (?:a )?(?:text|message|sms)(?: message)? to)\\s+(.+)$").find(t)?.let {
            return smsPlan(it.groupValues[1], false)
        }
        if (Regex("^(?:call|phone|dial|ring)$").matches(t)) return Plan("call")
        Regex("^(?:call|phone|dial|ring)\\s+(.+)$").find(t)?.let { return Plan("call", target = it.groupValues[1].trim()) }
        Regex("^(?:make a (?:phone )?call to|give)\\s+(.+?)(?:\\s+a (?:call|ring))?$").find(t)?.let {
            return Plan("call", target = it.groupValues[1].trim())
        }

        // ---- maths / conversions
        calc(t)?.let { return it }
        convert(t)?.let { return it }

        // ---- quick settings, volume, info
        Regex("\\b(wi-?fi|bluetooth|airplane mode|hotspot)\\b").find(t)?.let { m ->
            val v = when {
                m.value.startsWith("wi") -> "wifi"
                m.value.startsWith("blue") -> "bluetooth"
                m.value.startsWith("air") -> "airplane"
                else -> "hotspot"
            }
            return Plan("settings", value = v)
        }
        if (Regex("\\b(volume up|louder|turn (?:it|the volume) up|increase (?:the )?volume|raise (?:the )?volume)\\b").containsMatchIn(t)) return Plan("volume", value = "up")
        if (Regex("\\b(volume down|quieter|turn (?:it|the volume) down|decrease (?:the )?volume|lower (?:the )?volume)\\b").containsMatchIn(t)) return Plan("volume", value = "down")
        if (Regex("^(?:mute|silence)(?: (?:the )?(?:phone|volume|sound))?$").matches(t)) return Plan("volume", value = "mute")

        if (Regex("^(?:what(?:'s| is) the time|what time is it|current time|tell me the time|time please)(?: now| right now)?$").matches(t)) return Plan("time")
        if (Regex("^(?:what(?:'s| is)(?: today'?s| the) date|today'?s date|what day is (?:it|today)|what is today|what(?:'s| is) today)$").matches(t)) return Plan("date")
        if (Regex("^(?:what(?:'s| is)(?: my| the)? battery.*|how much battery.*|battery.*|check (?:my )?battery.*|how(?:'s| is) my battery.*)$").matches(t)) return Plan("battery")
        if (Regex("^(?:read|check|show)(?: me)?(?: my)?(?: new| latest| unread)? (?:notifications|messages|texts)$|^(?:do i have|any)(?: any)?(?: new)? (?:notifications|messages)$|^what did i miss$").matches(t)) return Plan("notifications")
        if (Regex("^(?:take (?:a )?(?:photo|picture|pic|selfie)|open (?:the )?camera|(?:camera|selfie))$").matches(t)) {
            return Plan("camera", value = if (t.contains("selfie")) "selfie" else "")
        }

        // ---- places, navigation, music, search, apps
        Regex("^(?:navigate to|navigate|directions to|take me to|how do i get to)\\s+(.+)$").find(t)?.let {
            return Plan("navigate", target = it.groupValues[1].trim())
        }
        Regex("^(?:find|show me|search for|look for)?\\s*(?:the )?(?:nearest|closest|nearby)\\s+(.+)$").find(t)?.let {
            return Plan("maps", target = it.groupValues[1].trim() + " near me")
        }
        Regex("^(?:find|show me|search for|look for)\\s+(.+?)\\s+near me$").find(t)?.let {
            return Plan("maps", target = it.groupValues[1].trim() + " near me")
        }
        Regex("^play\\s+(.+)$").find(t)?.let { m ->
            var q = m.groupValues[1].trim()
            var where = ""
            Regex("\\s+on\\s+(youtube|spotify)$").find(q)?.let { w ->
                where = w.groupValues[1]
                q = q.removeRange(w.range).trim()
            }
            return Plan("play_music", target = q, value = where)
        }
        Regex("^(?:search for|search|google|look up)\\s+(.+)$").find(t)?.let {
            return Plan("search", target = it.groupValues[1].trim())
        }
        Regex("^(?:open|launch|start)\\s+(.+)$").find(t)?.let {
            return Plan("open_app", target = it.groupValues[1].trim())
        }
        return null
    }

    // ------------------------------------------------------------------ pending question answers

    /** Fills in a half-finished command with the user's answer. Returns null if the answer isn't usable. */
    fun complete(pending: Plan, answerRaw: String, now: Int = nowMinutes()): Plan? {
        val a = answerRaw.trim().trimEnd('.', '!', '?', ',', ' ')
        if (a.isEmpty()) return null
        if (Regex("^(?:stop|cancel|never ?mind|forget it|no|nope|nothing)$", RegexOption.IGNORE_CASE).matches(a)) return Plan("noop")
        return when (pending.action) {
            "call", "navigate", "search", "play_music", "open_app", "maps", "weather" ->
                pending.copy(target = a.removePrefix("call ").removePrefix("to ").trim())
            "sms" ->
                if (pending.target.isBlank()) pending.copy(target = a) else pending.copy(message = a)
            "note_add" -> pending.copy(message = a)
            "alarm" -> parseClock(a, now)?.let { pending.copy(hour = it.first, minute = it.second) }
            "timer" -> parseDuration(a)?.let { pending.copy(seconds = it) }
            "reminder" -> when {
                pending.message.isBlank() -> pending.copy(message = a)
                else -> {
                    val secs = parseDuration(a)
                    val clock = parseClock(a, now)
                    when {
                        secs != null -> pending.copy(seconds = secs, value = "timer")
                        clock != null -> pending.copy(hour = clock.first, minute = clock.second, value = "alarm")
                        else -> null
                    }
                }
            }
            else -> null
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun media(t: String): Plan? {
        val v = when {
            Regex("^pause(?: (?:the )?(?:music|song|video|playback|track))?$").matches(t) -> "pause"
            Regex("^(?:resume|unpause|continue playing|play|resume (?:the )?(?:music|song|video|playback))$").matches(t) -> "play"
            Regex("^(?:next|skip|play next|next (?:song|track)|skip (?:this )?(?:song|track)|play next (?:song|track))$").matches(t) -> "next"
            Regex("^(?:previous|previous (?:song|track)|go back|last song|play (?:the )?previous(?: song| track)?)$").matches(t) -> "prev"
            Regex("^stop (?:the )?(?:music|song|playback|video)$").matches(t) -> "stop"
            else -> return null
        }
        return Plan("media", value = v)
    }

    private fun smsPlan(rest0: String, whatsapp0: Boolean): Plan {
        var rest = rest0
        var wa = whatsapp0
        if (rest.contains(" on whatsapp")) { wa = true; rest = rest.replace(" on whatsapp", "") }
        val split = Regex("\\s+(?:saying that|saying|that says|and say|to say|say that|that)\\s+").find(rest)
        val target = if (split != null) rest.substring(0, split.range.first) else rest
        val msg = if (split != null) rest.substring(split.range.last + 1) else ""
        return Plan("sms", target = target.trim(), message = msg.trim(), value = if (wa) "whatsapp" else "")
    }

    private fun calc(t: String): Plan? {
        val body = Regex("^(?:calculate|compute|work out|solve|what(?:'s| is)|whats|how much is)\\s+(?:the\\s+)?(.+)$").find(t)?.groupValues?.get(1) ?: t
        val d = Num.digitize(body)
        val looksLikeMaths = Regex(
            "\\d\\s*(?:plus|minus|times|multiplied by|divided by|over|into|x|percent of|%\\s*of|to the power of|squared|cubed|[+*/^×÷])\\s*\\d|\\d\\s+-\\s+\\d|(?:square )?root of \\d|\\d\\s*(?:squared|cubed)"
        ).containsMatchIn(d)
        if (!looksLikeMaths) return null
        val v = Calc.fromSpeech(body)
        if (v == null) {
            return if (d.contains("/") || d.contains("divided") || d.contains(" over ")) Plan("answer", reply = "That isn't something I can calculate.") else null
        }
        return Plan("answer", reply = "${body.trim()} is ${Calc.format(v)}.")
    }

    private fun convert(t: String): Plan? {
        val d = Num.digitize(t)
        Regex("(\\d+(?:\\.\\d+)?)\\s*(?:degrees?\\s+)?([a-z]+)\\s+(?:to|in|into|as)\\s+(?:degrees?\\s+)?([a-z]+)").find(d)?.let { m ->
            val amount = m.groupValues[1].toDouble()
            val from = m.groupValues[2]
            val to = m.groupValues[3]
            val fc = Units.currencyCode(from)
            val tc = Units.currencyCode(to)
            if (fc != null && tc != null) return Plan("currency", target = fc, message = tc, value = Calc.format(amount, 4))
            Units.convertSpeech(amount, from, to)?.let { return Plan("answer", reply = it) }
        }
        Regex("how many ([a-z]+) (?:are )?(?:there )?in (?:an? )?(\\d+(?:\\.\\d+)?\\s+)?([a-z]+)").find(d)?.let { m ->
            val to = m.groupValues[1]
            val amount = m.groupValues[2].trim().ifEmpty { "1" }.toDouble()
            val from = m.groupValues[3]
            val fc = Units.currencyCode(from)
            val tc = Units.currencyCode(to)
            if (fc != null && tc != null) return Plan("currency", target = fc, message = tc, value = Calc.format(amount, 4))
            Units.convertSpeech(amount, from, to)?.let { return Plan("answer", reply = it) }
        }
        return null
    }

    private fun reminder(body0: String, now: Int): Plan? {
        val body = Num.digitize(body0.trim())
        fun cleanLabel(s: String) = s.replace(Regex("\\b(?:tomorrow|today|tonight)\\b"), " ")
            .replace(Regex("\\s+"), " ").trim()
            .removePrefix("to ").removePrefix("about ").removePrefix("that ").trim()

        // "in 10 minutes"
        Regex("\\bin\\s+(?=(?:\\d|an?\\s+(?:hour|minute|second|hr|min|sec)|half))").find(body)?.let { m ->
            val tail = body.substring(m.range.last + 1)
            val cut = Regex("\\s+(?:to|that|about)\\s+").find(tail)
            val phrase = if (cut != null) tail.substring(0, cut.range.first) else tail
            val secs = parseDuration(phrase)
            if (secs != null) {
                val after = if (cut != null) " " + tail.substring(cut.range.last + 1) else ""
                val label = cleanLabel(body.substring(0, m.range.first) + after)
                return Plan("reminder", message = label, seconds = secs, value = "timer")
            }
        }
        // "at 5 pm"
        val timePhrase = Regex("\\b(?:at|by|around)\\s+(?:noon|midnight|\\d{1,2}(?:[:.\\s]?\\d{2})?\\s*(?:[ap]m)?)\\b")
        val tm = timePhrase.find(body)
        if (tm != null) {
            val c = parseClock(tm.value, now)
            if (c != null) {
                val label = cleanLabel(body.removeRange(tm.range))
                return Plan("reminder", message = label, hour = c.first, minute = c.second, value = "alarm")
            }
        }
        return Plan("reminder", message = cleanLabel(body))
    }

    /** Next clock time for an ambiguous hour like "7" (could be 7 am or 7 pm): whichever comes next. */
    fun nextOccurrence(h12: Int, minute: Int, now: Int): Pair<Int, Int> {
        val am = (h12 % 12) * 60 + minute
        val pm = am + 720
        val pick = when {
            am > now -> am
            pm > now -> pm
            else -> am
        }
        return (pick / 60) to (pick % 60)
    }

    fun parseClock(text: String, now: Int): Pair<Int, Int>? {
        val t = Num.digitize(text.lowercase()).replace(Regex("\\b([ap])\\.\\s?m\\b\\.?"), "$1m")
            .replace("o'clock", "").replace("o clock", "")
        if (Regex("\\bnoon\\b").containsMatchIn(t)) return 12 to 0
        if (Regex("\\bmidnight\\b").containsMatchIn(t)) return 0 to 0

        Regex("(\\d{1,2})(?:[:.\\s]?(\\d{2}))?\\s*([ap])m\\b").find(t)?.let { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].ifEmpty { "0" }.toInt()
            if (h in 1..12 && min in 0..59) return (((h % 12) + if (m.groupValues[3] == "p") 12 else 0)) to min
        }
        Regex("\\b(\\d{1,2}):([0-5]\\d)\\b").find(t)?.let { m ->
            val raw = m.groupValues[1]
            val h = raw.toInt()
            val min = m.groupValues[2].toInt()
            if (h > 23) return null
            return if (h > 12 || h == 0 || raw.startsWith("0")) h to min else nextOccurrence(h, min, now)
        }
        Regex("(?:at|for|by|around)\\s+(\\d{1,2})(?:[:.](\\d{2}))?\\b(?!\\s*(?:minutes?|mins?|hours?|hrs?|seconds?|secs?|%|percent))").find(t)?.let { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].ifEmpty { "0" }.toInt()
            if (h > 23 || min > 59) return null
            return if (h > 12 || h == 0) h to min else nextOccurrence(h, min, now)
        }
        // an answer like just "7" or "7 30" when we asked "what time?"
        Regex("^(\\d{1,2})(?:[:.\\s](\\d{2}))?$").find(t.trim())?.let { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].ifEmpty { "0" }.toInt()
            if (h > 23 || min > 59) return null
            return if (h > 12 || h == 0) h to min else nextOccurrence(h, min, now)
        }
        return null
    }

    fun parseDuration(text: String): Int? {
        var t = Num.digitize(text.lowercase())
        t = t.replace(Regex("\\bhalf an? hour\\b"), "30 minutes")
        t = t.replace(Regex("\\b(a|an)\\s+(?=(?:hour|hr|minute|min|second|sec)s?\\b)"), "1 ")
        t = t.replace(Regex("(\\d+)\\s*(hours?|hrs?|minutes?|mins?)\\s+and a half"), "$1.5 $2")
        t = t.replace(Regex("(\\d+) and a half (hours?|hrs?|minutes?|mins?)"), "$1.5 $2")
        var total = 0.0
        var found = false
        Regex("(\\d+(?:\\.\\d+)?)\\s*(hours?|hrs?|minutes?|mins?|seconds?|secs?)\\b").findAll(t).forEach { m ->
            val n = m.groupValues[1].toDouble()
            val u = m.groupValues[2]
            total += when {
                u.startsWith("h") -> n * 3600
                u.startsWith("m") -> n * 60
                else -> n
            }
            found = true
        }
        return if (found && total > 0) total.roundToInt() else null
    }
}
