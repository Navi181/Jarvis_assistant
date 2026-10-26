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
        if (!Regex("^(?:translate|how (?:do|would) (?:you|i) say)\\b").containsMatchIn(t) && Regex("\\b(?:good morning|morning briefing|daily briefing|brief me|my briefing|what(?:'s| is) my day)\\b").containsMatchIn(t)) return Plan("briefing")
        // ---- money, habits, lists, calendar changes, library
        Regex("^(?:i )?(?:spent|paid|used)\\s+(?:rs\\.?\\s*|rupees\\s*|₹\\s*)?(\\d+(?:\\.\\d+)?)(?:\\s*(?:rupees|rs|bucks))?(?:\\s+(?:on|for|at))?\\s*(.*)$").find(t)?.let {
            return Plan("expense_add", value = it.groupValues[1], message = it.groupValues[2].trim())
        }
        Regex("^(?:how much (?:did|have) i (?:spend|spent)|(?:show |what(?:'s| are) )?my (?:expenses|spending))(?: (today|yesterday|this week|this month|week|month))?$").find(t)?.let {
            return Plan("expense_report", value = it.groupValues[1].ifBlank { "today" })
        }
        Regex("^(?:track|start tracking|add)(?: a| the)? habit(?: of| called)? (.+)$|^(?:track|start tracking) (?:my )?habit (.+)$").find(t)?.let {
            return Plan("habit_add", target = (it.groupValues[1].ifBlank { it.groupValues[2] }).trim())
        }
        Regex("^(?:log|mark|check in)(?: my| the)?(?: habit)? (.+?)(?: as)?(?: done| complete| completed)?(?: for)?(?: today)?$").find(t)?.let {
            if (!Regex("\\b(?:call|sms|message|reminder|alarm|note|timer)\\b").containsMatchIn(it.groupValues[1])) return Plan("habit_log", target = it.groupValues[1].trim())
        }
        Regex("^(?:how(?:'s| is) my|what(?:'s| is) my|show my) (.+?) streak$").find(t)?.let { return Plan("habit_show", target = it.groupValues[1].trim()) }
        if (Regex("^(?:my habits|show my habits|how am i doing on my habits)$").matches(t)) return Plan("habit_show")
        Regex("^(?:add|put) (.+?) (?:to|on|in) my (?:(\\w+) )?(?:list|to-?do)$|^add (.+?) to (?:the )?(\\w+) list$").find(t)?.let {
            val items = it.groupValues[1].ifBlank { it.groupValues[3] }
            val name = it.groupValues[2].ifBlank { it.groupValues[4] }.ifBlank { "shopping" }
            if (items.isNotBlank() && !Regex("\\b(?:playlist|queue|dictionary|contacts?|calendar|cart)\\b").containsMatchIn(t)) return Plan("list_add", target = name, message = items)
        }
        Regex("^(?:what(?:'s| is) on|read|show|open) my (?:(\\w+) )?list$").find(t)?.let { return Plan("list_show", target = it.groupValues[1].ifBlank { "shopping" }) }
        Regex("^(?:remove|delete|take off) (.+?) (?:from|off) my (?:(\\w+) )?list$").find(t)?.let { return Plan("list_remove", target = it.groupValues[2].ifBlank { "shopping" }, message = it.groupValues[1]) }
        Regex("^(?:clear|empty) my (?:(\\w+) )?list$").find(t)?.let { return Plan("list_clear", target = it.groupValues[1].ifBlank { "shopping" }) }
        Regex("^(?:share|send) my (?:(\\w+) )?list$").find(t)?.let { return Plan("list_share", target = it.groupValues[1].ifBlank { "shopping" }) }
        Regex("^(?:move|reschedule|shift|push) (?:my )?(.+?) (?:to|at|till|until) (.+)$").find(t)?.let { m ->
            val c = parseClock(m.groupValues[2], now)
            if (c != null && !Regex("\\b(?:alarm|timer|reminder)\\b").containsMatchIn(m.groupValues[1])) return Plan("cal_move", target = m.groupValues[1].replace(Regex(" (?:meeting|event|appointment|call)$"), "").trim(), hour = c.first, minute = c.second)
        }
        Regex("^(?:cancel|delete|remove) (?:my |the )?(.+?) (?:meeting|event|appointment)$").find(t)?.let { return Plan("cal_cancel", target = it.groupValues[1].trim()) }
        Regex("^(?:what(?:'s| is)|when am i|am i|when(?:'s| is)) (?:my )?(?:free|available|open)(?: time)?(?: (today|tomorrow))?$|^when am i free(?: (today|tomorrow))?$").find(t)?.let {
            return Plan("cal_free", value = it.groupValues[1].ifBlank { it.groupValues[2] }.ifBlank { "today" })
        }
        if (Regex("^(?:save|add|keep|remember) (?:this|it)(?: (?:file|photo|picture|pdf|bill|receipt|document|image))?(?: to (?:my )?(?:library|saved files))?$|^save (?:this|it) to (?:my )?library$").matches(t) &&
            (try { Assistant.media.value != null } catch (e: Throwable) { false })) return Plan("lib_save")
        Regex("^(?:save|add|keep) (?:this|it) (?:as|called|named) (.+)$").find(t)?.let {
            if (try { Assistant.media.value != null } catch (e: Throwable) { false }) return Plan("lib_save", message = it.groupValues[1].trim())
        }
        Regex("^(?:ask my library|from my library|in my library|in my saved files|search my library for|search my saved files for|find in my library)[:,]? (.+)$").find(t)?.let { return Plan("lib_ask", message = it.groupValues[1].trim()) }
        Regex("^(?:what|when|how much|who|which) (?:was|is|were) (?:the )?(.+?) (?:on|in|from) (?:my |the |that )?(?:saved )?(?:bill|receipt|invoice|ticket|document|file|photo|pdf|statement)s?$").find(t)?.let { return Plan("lib_ask", message = t) }
        Regex("^when i say (.+?) i (?:mean|want|meant) (.+)$").find(t)?.let { return Plan("fix_add", target = it.groupValues[1].trim(), value = it.groupValues[2].trim()) }

        // ---- voice layer: type, edit and ask about what is in front of you
        if (Regex("^(?:start |begin |turn on )?(?:dictation|dictate)(?: mode)?$").matches(t)) return Plan("dictate")
        Regex("^(?:dictate|type out|type)\\s+(.{2,})$").find(t)?.let { return Plan("dictate", message = it.groupValues[1].trim()) }
        if (Regex("^(?:(?:make|rewrite|rephrase|reword|shorten|expand|improve|polish|proofread|correct|fix|translate|summari[sz]e|change)\\s+(?:this|that|the selection|the selected text|the text|my text|my message|my reply)\\b.*|(?:fix|correct) (?:the |my )?(?:grammar|spelling|typos|mistakes)|make it (?:shorter|longer|better|more .+|less .+)|(?:shorten|rewrite|rephrase|proofread) it)$").matches(t) &&
            !Regex("\\b(?:screen|page)\\b").containsMatchIn(t) && !Regex("^(?:summari[sz]e|translate) (?:this|that)$").matches(t)) return Plan("edit_text", message = t)
        if (Regex("^(?:summari[sz]e|translate|explain|read|simplify) (?:this|that|the|my)? ?(?:screen|page|article|chat|conversation|message|email|text|post)(?: .+)?$|^(?:summari[sz]e|translate|explain) (?:this|that)(?: .+)?$|^what does (?:this|that) (?:say|mean)$|^what(?:'s| is) this (?:about|page|screen)$").matches(t))
            return Plan("screen_ask", message = t)
        Regex("^(?:add|put|save) (.+?) (?:to|in|into) my dictionary$").find(t)?.let { return Plan("dict_add", value = it.groupValues[1].replace(Regex("\\s+and\\s+"), ", ").trim()) }
        if (Regex("^(?:what(?:'s| is) in |show |read |list )?my dictionary$").matches(t)) return Plan("dict_list")

        // ---- links, news digest
        Regex("^(?:read|summari[sz]e)(?: out| aloud)?(?: me)? (?:this |that |the )?(https?://\\S+)$").find(lower.trim().trimEnd('.', '!', '?', ' '))?.let {
            return Plan("read_link", target = Regex("https?://\\S+").find(raw)?.value?.trimEnd('.', ',', ')') ?: it.groupValues[1])
        }
        if (Regex("^(?:read|summari[sz]e)(?: out| aloud)?(?: me)?(?: this| that| the)? (?:link|article|page|url|copied link|copied text)(?: aloud| out)?$").matches(t)) return Plan("read_link")
        if (Regex("^(?:give me |read |what'?s |tell me |play )?(?:my |the )?(?:daily |morning )?(?:news digest|news briefing|news brief|digest|morning news)$").matches(t) || t == "brief me on the news") return Plan("news_digest")
        Regex("^(?:set |change )?my news topics(?: are| to)? (.+)$").find(t)?.let { return Plan("news_topics", value = it.groupValues[1].replace(Regex("\\s+and\\s+"), ", ").trim()) }

        // ---- translation and parking
        if (Regex("^(?:stop|exit|end|turn off|leave) (?:the )?(?:translating|translation|translator)(?: mode)?$").matches(t)) return Plan("translate_off")
        Regex("^(?:translate|interpret)(?: everything| what i say)?(?: for me)? (?:in)?to ([a-z ]+?)$|^(?:start |turn on |enter )?(?:translation|translator|interpreter) mode(?: (?:in)?to ([a-z ]+?))?$").find(t)?.let {
            val lang = (it.groupValues[1].ifBlank { it.groupValues[2] }).trim()
            return Plan("translate_on", value = lang.ifBlank { "English" })
        }
        Regex("^(?:translate (.+?) (?:in|into|to)|how (?:do|would) (?:you|i) say (.+?) (?:in|into)) ([a-z]+(?: [a-z]+)?)$").find(t)?.let {
            val w = it.groupValues[1].ifBlank { it.groupValues[2] }.trim()
            if (!w.startsWith("everything")) return Plan("translate", message = w, value = it.groupValues[3].trim())
        }
        if (Regex("^(?:where (?:did i|have i|is my car|was i)(?: park| parked)?(?: the car| my car)?|where(?:'s| is) my car(?: parked)?|where did i park(?: my car| the car)?)$").matches(t)) return Plan("park_where")
        Regex("^(?:i )?(?:parked|park(?:ed)? my car|remember where i parked)(?: (?:the car|my car|it))?(?: (?:at|on|in|near|by|outside|behind|next to|opposite)? ?(.*))?$").find(t)?.let {
            val loc = it.groupValues[1].trim()
            val whole = t.removePrefix("i ").removePrefix("parked").removePrefix(" my car").removePrefix(" the car").trim()
            return Plan("park_save", message = if (t.startsWith("remember")) "" else (if (whole.isBlank()) "" else whole.ifBlank { loc }))
        }

        // ---- routines that run by themselves, and reminders that repeat until you say done
        Regex("^(?:run|start|do|schedule|activate|set up)\\s+(?:my |the )?(.+?)(?: routine)?\\s+(?:(?:every ?day|daily|each day|every (?:morning|night|evening))\\s+)?(?:at|by|for)\\s+(.+?)(?:\\s+(?:every ?day|daily|each day))?$").find(t)?.let { m ->
            val c = parseClock(m.groupValues[2], now)
            if (c != null && Routines.find(m.groupValues[1]) != null) return Plan("routine_schedule", target = m.groupValues[1].trim(), hour = c.first, minute = c.second)
        }
        Regex("^(?:stop|cancel|unschedule|remove|delete) (?:my |the )?(?:scheduled |daily )(.+?)(?: routine)?$|^(?:stop|cancel|unschedule|remove) (?:the )?(.+?) (?:schedule|every day)$").find(t)?.let {
            return Plan("routine_unschedule", target = (it.groupValues[1].ifBlank { it.groupValues[2] }).trim())
        }
        if (Regex("^(?:what(?:'s| is| are)|list|show) (?:my )?(?:scheduled|daily) (?:routines|things|tasks)$").matches(t)) return Plan("routine_scheduled")
        Regex("^(?:keep reminding me|nag me|remind me)(?: to| about)? (.+?) every (.+?) until (?:i )?(?:say |tell you |said )?(?:done|stop|i(?:'m| am) done|it'?s done)$").find(t)?.let {
            return Plan("nag", message = it.groupValues[1].trim(), seconds = parseDuration(it.groupValues[2]) ?: 300)
        }
        Regex("^(?:keep reminding me|nag me)(?: to| about)? (.+)$").find(t)?.let { return Plan("nag", message = it.groupValues[1].trim(), seconds = 300) }
        if (Regex("^(?:stop (?:reminding|nagging) me|stop the reminders?|stop nagging)$").matches(t)) return Plan("nag_stop")
        if (Regex("^(?:done|i(?:'m| am| have)? done|i did it|finished|it'?s done|okay i did it|i have done it)$").matches(t) &&
            (try { Prefs.nagMessage.isNotBlank() } catch (e: Exception) { false })) return Plan("nag_stop")

        Routines.parse(t)?.let { return it }

        // "do this and then that": several commands in one go
        if (Regex("\\b(?:and then|, then|then|and also|after that)\\b").containsMatchIn(t) &&
            !Regex("^(?:remind|remember|note|text|message|send|reply|create|set a reminder|write|dictate|type)\\b").containsMatchIn(t) &&
            Routines.splitSteps(t).size >= 2) return Plan("chain", message = t)

        // ---- look at something with the camera
        if (Regex("^(?:take a )?(?:look at (?:this|that)|what(?:'s| is) (?:this|that)(?: thing)?|what am i (?:looking|pointing) at|identify (?:this|that)|scan this)$").matches(t)) return Plan("look")

        // ---- reply to the latest message notification (always confirmed first)
        Regex("^(?:reply|respond|text back|message back|whatsapp back)(?: back)?(?: to (?:the |that |this )?(?:last |latest |recent |previous )?(?:message|text|one))?(?: to (.+?))?\\s+(?:saying|say|with|that says|that|:)\\s*(.+)$").find(t)?.let {
            return Plan("reply_msg", target = it.groupValues[1].trim(), message = it.groupValues[2].trim())
        }
        Regex("^reply (?!to\\b)(.+)$").find(t)?.let { return Plan("reply_msg", message = it.groupValues[1].trim()) }

        // ---- go into depth on the last answer or any subject
        if (Brain.wantsDetail(t) && !Regex("^(?:play|call|open|send|set|add|navigate|turn|remind|text|message)\\b").containsMatchIn(t)) {
            return Plan("elaborate", target = t)
        }

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

        // ---- memory and screen
        if (Regex("^(?:forget everything(?: you know about me)?|clear (?:your )?memory|forget what i told you)$").matches(t)) return Plan("forget_all")
        if (Regex("^(?:what do you (?:remember|know) about me|what(?:'s| is) in your memory|what did i tell you to remember)$").matches(t)) return Plan("memory_read")
        Regex("^(?:remember|keep in mind|don't forget|dont forget)(?: that)?\\s+(?!to\\b)(.+)$").find(t)?.let { return Plan("remember", message = it.groupValues[1].trim()) }
        if (Regex("^(?:what(?:'s| is) on (?:my|the) screen|read (?:my|the|this) screen|(?:summari[sz]e|explain) (?:this|my|the) (?:screen|page)|what am i looking at)\\??$").matches(t)) return Plan("screen_read")

        // ---- shopping, installing apps, calendar
        Regex("^(?:search|find|look (?:for|up)|show me|buy|order|check)\\s+(.+?)\\s+(?:on|in|at|from)\\s+(amazon|flipkart)(?:\\s+.*)?$").find(t)?.let {
            return Plan("shop", target = it.groupValues[1].replace(Regex("^(?:the )?(?:price of|prices? for)\\s+"), "").trim(), value = it.groupValues[2])
        }
        Regex("^(?:install|download|get me)(?: the)?(?: app)?\\s+(.+?)(?: app)?(?: from (?:the )?play ?store)?$").find(t)?.let {
            if (!it.groupValues[1].contains("update")) return Plan("install_app", target = it.groupValues[1].trim())
        }

        if (t.contains("calendar") && Regex("^(?:please )?(?:add|put|block|block out|schedule|create|set up|book|make|mark)\\b").containsMatchIn(t)) {
            return calendarPlan(t, now)
        }
        Regex("^(?:what(?:'s| is)?(?: is)? on my (?:calendar|schedule)|what(?:'s| is) my (?:schedule|agenda)|do i have any (?:meetings?|events?|plans)|my (?:schedule|agenda|calendar))\\s*(today|tomorrow)?$").find(t)
            ?.let { return Plan("agenda", value = it.groupValues[1].ifBlank { "today" }) }
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

        // ---- on/off switches (done for you through the screen, not just opened)
        val sw = "(wi-?fi|bluetooth|airplane mode|flight mode|hotspot|mobile hotspot|mobile data|data|location|gps|do not disturb|dnd|battery saver|power saving|auto rotate|screen rotation|rotation|nfc|dark mode|dark theme|night light|eye comfort|screen mirroring|smart view|cast|screen recorder|auto sync|sync|always on display|torch|invert colou?rs)"
        Regex("^(?:please )?(?:turn|switch|put|set)\\s+(on|off)\\s+(?:the |my )?$sw$").find(t)?.let {
            return Plan("toggle", target = it.groupValues[2], value = it.groupValues[1])
        }
        Regex("^(?:please )?(?:turn|switch)\\s+(?:the |my )?$sw\\s+(on|off)$").find(t)?.let {
            return Plan("toggle", target = it.groupValues[1], value = it.groupValues[2])
        }
        Regex("^(?:please )?(enable|disable|activate|deactivate)\\s+(?:the |my )?$sw$").find(t)?.let {
            return Plan("toggle", target = it.groupValues[2], value = if (it.groupValues[1].startsWith("en") || it.groupValues[1].startsWith("act")) "on" else "off")
        }

        // ---- phone controls and screen
        if (Regex("^(?:take (?:a )?screenshot|screenshot|capture (?:the )?screen)$").matches(t)) return Plan("global", value = "screenshot")
        if (Regex("^(?:lock (?:the |my )?(?:phone|screen)|lock it)$").matches(t)) return Plan("global", value = "lock")
        if (Regex("^go back$").matches(t)) return Plan("global", value = "back")
        if (Regex("^go (?:to )?(?:the )?home(?: screen)?$").matches(t)) return Plan("global", value = "home")
        if (Regex("^(?:show|open) (?:my )?(?:recent apps|recents)$").matches(t)) return Plan("global", value = "recents")
        if (Regex("^(?:pull down|show|open) (?:the )?quick settings$").matches(t)) return Plan("global", value = "quick_settings")
        if (Regex("^(?:what(?:'s| is) on (?:my|the) screen|read (?:my|the|this) screen|(?:summari[sz]e|explain) (?:my |the |this )?screen)$").matches(t)) return Plan("screen_read")
        Regex("^(?:email|send (?:an )?email to|mail)\\s+(.+?)(?:\\s+(?:saying that|saying|that says|about|and say|to say)\\s+(.+))?$").find(t)?.let {
            return Plan("email", target = it.groupValues[1].trim(), message = it.groupValues[2].trim())
        }

        // ---- volume level, ringer mode, brightness, power menu
        val td = Num.digitize(t)
        Regex("(?:^|\\b)(?:set |put |turn |change )?(?:the )?(media |music |ring(?:er|tone)? |alarm |notification )?volume (?:to|at|on)\\s+(\\d{1,3})\\s*(?:%|percent)?").find(td)?.let {
            return Plan("volume_set", target = it.groupValues[1].trim(), value = it.groupValues[2])
        }
        if (Regex("^(?:max(?:imum)?|full) volume$|^volume (?:to )?(?:max|full|maximum)$").matches(t)) return Plan("volume_set", value = "100")
        Regex("^(?:please )?(?:put|set|switch|turn)?\\s*(?:my |the )?(?:phone )?(?:on |to |into )?(silent|vibrate|vibration|normal|sound|ring)(?: mode)?(?: on)?$").find(t)?.let {
            return Plan("ringer", value = it.groupValues[1])
        }
        if (Regex("\\bbrightness\\b|\\b(?:dim|brighten)\\b|screen (?:brighter|dimmer)").containsMatchIn(td) && !td.contains("setting")) {
            val n = Regex("(\\d{1,3})\\s*(?:%|percent)?").find(td)?.groupValues?.get(1)
            val v = when {
                n != null -> n
                Regex("\\b(max|maximum|full|highest)\\b").containsMatchIn(td) -> "100"
                Regex("\\b(min|minimum|lowest)\\b").containsMatchIn(td) -> "5"
                Regex("\\b(up|increase|brighter|brighten|higher|raise)\\b").containsMatchIn(td) -> "up"
                Regex("\\b(down|decrease|dim|dimmer|lower|reduce)\\b").containsMatchIn(td) -> "down"
                else -> ""
            }
            return Plan("brightness", value = v)
        }
        if (Regex("^(?:show |open |pull down )?(?:the )?(?:power menu|shut ?down(?: the phone)?|power off|switch off the phone|restart(?: the phone)?|reboot)$").matches(t)) return Plan("global", value = "power")
        if (Regex("^(?:show |open |pull down )(?:my |the )?notifications?(?: panel| shade| bar)?$").matches(t)) return Plan("global", value = "notifications")
        if (Regex("^(?:toggle |turn on |start )?split ?screen$").matches(t)) return Plan("global", value = "split")
        Regex("^(?:open|show|go to|take me to)\\s+(?:the |my )?(.+?)\\s+settings$").find(t)?.let {
            return Plan("settings", value = it.groupValues[1].trim())
        }

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
            Regex("\\s+(?:on|in|using|with)\\s+(youtube(?: music)?|spotify|amazon(?: music)?|prime music|joytify)$").find(q)?.let { w ->
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
        if (pending.action == "play_music" && pending.value == "?pick") return pending.copy(value = a)
        return when (pending.action) {
            "call", "navigate", "search", "play_music", "open_app", "maps", "weather" ->
                pending.copy(target = a.removePrefix("call ").removePrefix("to ").trim())
            "sms" ->
                if (pending.target.isBlank()) pending.copy(target = a) else pending.copy(message = a)
            "note_add" -> pending.copy(message = a)
            "park_save" -> pending.copy(message = a)
            "dictate" -> pending.copy(message = answerRaw.trim())
            "cal_cancel" -> if (Regex("^(?:yes|yeah|yep|yup|sure|do it|ok|okay|confirm|please do|go ahead)\\b", RegexOption.IGNORE_CASE).containsMatchIn(a)) pending.copy(value = "yes") else Plan("noop")
            "list_add" -> pending.copy(message = a)
            "reply_msg" -> pending.copy(message = a)
            "reply_send" ->
                if (Regex("^(?:yes|yeah|yep|yup|sure|send|send it|go ahead|do it|ok|okay|please do|confirm)\\b", RegexOption.IGNORE_CASE).containsMatchIn(a)) pending.copy(value = "yes") else Plan("noop")
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

    private fun calendarPlan(t0: String, now: Int): Plan {
        var t = Num.digitize(t0)
        var value = "today"
        if (Regex("\\btomorrow\\b").containsMatchIn(t)) value = "tomorrow"
        Regex("\\b(?:on |next )?(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b").find(t)?.let { m ->
            val dow = java.time.DayOfWeek.valueOf(m.groupValues[1].uppercase())
            val d = if (m.value.startsWith("next")) java.time.LocalDate.now().with(java.time.temporal.TemporalAdjusters.next(dow))
            else java.time.LocalDate.now().with(java.time.temporal.TemporalAdjusters.nextOrSame(dow))
            value = d.toString()
        }
        var seconds = 0
        Regex("\\bfor\\s+((?:\\d+|an?|half an?)\\s*(?:hours?|hrs?|minutes?|mins?))\\b").find(t)?.let { m ->
            seconds = parseDuration(m.groupValues[1]) ?: 0
            t = t.replace(m.value, " ")
        }
        val clock = parseClock(t, now)
        var title = Regex("(?:called|named|titled)\\s+(.+?)(?:\\s+(?:on|at|tomorrow|today|in|to)\\b.*)?$").find(t)?.groupValues?.get(1)
            ?: Regex("\\bfor\\s+(?!\\d)(.+?)(?:\\s+(?:on|in|to|at|tomorrow|today)\\b.*)?$").find(t)?.groupValues?.get(1)
            ?: ""
        title = title.replace(Regex("\\b(my|the|a|an)\\s+calendar\\b"), "").trim()
        if (title.isBlank()) {
            title = t.replace(Regex("\\b(please|add|put|block out|block|schedule|create|set up|book|make|mark|an?|the|my|event|meeting|slot|time|on|in|to|into|calendar|today|tomorrow|at|for)\\b"), " ")
                .replace(Regex("\\b\\d{1,2}(:\\d{2})?\\s*(am|pm)?\\b"), " ")
                .replace(Regex("\\b(monday|tuesday|wednesday|thursday|friday|saturday|sunday|next|called|named)\\b"), " ")
                .replace(Regex("\\s+"), " ").trim()
        }
        title = title.replaceFirstChar { it.uppercase() }.ifBlank { "Blocked time" }
        return Plan(
            "calendar", message = title, value = value, seconds = seconds,
            hour = clock?.first ?: -1, minute = clock?.second ?: 0
        )
    }

    private fun smsPlan(rest0: String, whatsapp0: Boolean): Plan {
        var rest = rest0
        var wa = whatsapp0
        if (rest.contains(" on whatsapp")) { wa = true; rest = rest.replace(" on whatsapp", "") }
        val split = Regex("\\s+(?:saying that|saying|that says|and say|to say|say that|that)\\s+").find(rest)
        val target = if (split != null) rest.substring(0, split.range.first) else rest
        val msg = if (split != null) rest.substring(split.range.last + 1) else ""
        val g = Regex("^(?:the\\s+)?(.+?)\\s+(?:whatsapp\\s+)?group(?:\\s+chat)?$").find(target.trim())
        if (g != null && g.groupValues[1].isNotBlank()) {
            return Plan("sms", target = g.groupValues[1].trim(), message = msg.trim(), value = "whatsapp_group")
        }
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
