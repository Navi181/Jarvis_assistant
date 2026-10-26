package com.jarvis.assistant.core

import java.time.LocalDate
import java.time.ZoneOffset

/** Table of phrases and what Jarvis must understand. Shared by the JUnit test and the quick runner. */
object ParserCases {
    const val NOW = 15 * 60 + 14 // 3:14 pm

    class Exp(
        val action: String,
        val target: String? = null,
        val message: String? = null,
        val hour: Int? = null,
        val minute: Int? = null,
        val seconds: Int? = null,
        val value: String? = null,
        val replyHas: String? = null
    )

    private val cases = ArrayList<Pair<String, Exp?>>()
    private fun c(phrase: String, e: Exp?) { cases.add(phrase to e) }

    init {
        // calls & messages
        c("call mom", Exp("call", target = "mom"))
        c("Hey Jarvis, call Rahul Sharma", Exp("call", target = "rahul sharma"))
        c("could you please call dad", Exp("call", target = "dad"))
        c("call", Exp("call", target = ""))
        c("call 9876543210", Exp("call", target = "9876543210"))
        c("give priya a call", Exp("call", target = "priya"))
        c("text rahul saying I'll be late", Exp("sms", target = "rahul", message = "i'll be late"))
        c("message mom that I reached home", Exp("sms", target = "mom", message = "i reached home"))
        c("whatsapp priya saying hi", Exp("sms", target = "priya", message = "hi", value = "whatsapp"))
        c("send a message to dad on whatsapp saying come home", Exp("sms", target = "dad", message = "come home", value = "whatsapp"))
        c("text anna", Exp("sms", target = "anna", message = ""))
        // timers
        c("set a timer for 10 minutes", Exp("timer", seconds = 600))
        c("timer for five minutes", Exp("timer", seconds = 300))
        c("set a timer for half an hour", Exp("timer", seconds = 1800))
        c("set a timer for an hour and a half", Exp("timer", seconds = 5400))
        c("set a timer for 1 hour 30 minutes", Exp("timer", seconds = 5400))
        c("start a 90 second timer", Exp("timer", seconds = 90))
        c("set a timer for two and a half minutes", Exp("timer", seconds = 150))
        c("set a timer", Exp("timer", seconds = 0))
        // alarms
        c("set an alarm for 7 am", Exp("alarm", hour = 7, minute = 0))
        c("set alarm for 6:30 pm", Exp("alarm", hour = 18, minute = 30))
        c("wake me up at 5 a.m.", Exp("alarm", hour = 5, minute = 0))
        c("set an alarm for seven thirty am", Exp("alarm", hour = 7, minute = 30))
        c("set an alarm for 19:45", Exp("alarm", hour = 19, minute = 45))
        c("set an alarm for 7", Exp("alarm", hour = 19, minute = 0))   // 3:14 pm now -> next 7 is 7 pm
        c("set an alarm for 3", Exp("alarm", hour = 3, minute = 0))    // 3 pm already passed -> next 3 is 3 am
        c("set an alarm for noon", Exp("alarm", hour = 12, minute = 0))
        c("set an alarm for midnight", Exp("alarm", hour = 0, minute = 0))
        c("wake me up in 20 minutes", Exp("timer", seconds = 1200))
        c("set an alarm", Exp("alarm", hour = -1))
        c("cancel my alarm", Exp("alarm_cancel"))
        // reminders
        c("remind me to call mom at 5 pm", Exp("reminder", message = "call mom", hour = 17, minute = 0, value = "alarm"))
        c("remind me in 10 minutes to check the oven", Exp("reminder", message = "check the oven", seconds = 600, value = "timer"))
        c("remind me to drink water in half an hour", Exp("reminder", message = "drink water", seconds = 1800, value = "timer"))
        c("remind me", Exp("reminder", message = ""))
        c("remind me to buy milk", Exp("reminder", message = "buy milk", hour = -1))
        c("set a reminder to pay rent at 9 am", Exp("reminder", message = "pay rent", hour = 9, minute = 0, value = "alarm"))
        // notes
        c("take a note buy milk and eggs", Exp("note_add", message = "buy milk and eggs"))
        c("note that the wifi password is on the fridge", Exp("note_add", message = "the wifi password is on the fridge"))
        c("read my notes", Exp("note_read"))
        c("clear my notes", Exp("note_clear"))
        // weather
        c("what's the weather", Exp("weather", target = ""))
        c("what's the weather like in Mumbai today", Exp("weather", target = "mumbai"))
        c("will it rain in delhi", Exp("weather", target = "delhi"))
        c("weather for today", Exp("weather", target = ""))
        c("how hot is it", Exp("weather"))
        // maths & conversion
        c("what is 15 percent of 2400", Exp("answer", replyHas = "360"))
        c("calculate 12 times 7 plus 3", Exp("answer", replyHas = "87"))
        c("what's 100 divided by 8", Exp("answer", replyHas = "12.5"))
        c("what is the square root of 144", Exp("answer", replyHas = "12"))
        c("what is 5 plus 3", Exp("answer", replyHas = " 8."))
        c("what is 2 to the power of 10", Exp("answer", replyHas = "1024"))
        c("what is 8 divided by 0", Exp("answer", replyHas = "isn't something"))
        c("convert 10 miles to km", Exp("answer", replyHas = "16.09 kilometers"))
        c("how many feet in a mile", Exp("answer", replyHas = "5280 feet"))
        c("what is 100 degrees celsius in fahrenheit", Exp("answer", replyHas = "212"))
        c("5 kg in pounds", Exp("answer", replyHas = "11.02 pounds"))
        c("convert 100 dollars to rupees", Exp("currency", target = "USD", message = "INR", value = "100"))
        c("how many rupees in 50 dollars", Exp("currency", target = "USD", message = "INR", value = "50"))
        // media & device
        c("pause", Exp("media", value = "pause"))
        c("pause the music", Exp("media", value = "pause"))
        c("next song", Exp("media", value = "next"))
        c("skip", Exp("media", value = "next"))
        c("previous track", Exp("media", value = "prev"))
        c("resume", Exp("media", value = "play"))
        c("play", Exp("media", value = "play"))
        c("stop the music", Exp("media", value = "stop"))
        c("turn on the flashlight", Exp("flashlight", value = "on"))
        c("turn off the torch", Exp("flashlight", value = "off"))
        c("flashlight", Exp("flashlight", value = "toggle"))
        c("volume up", Exp("volume", value = "up"))
        c("make it quieter", Exp("volume", value = "down"))
        c("turn it down", Exp("volume", value = "down"))
        c("mute", Exp("volume", value = "mute"))
        c("open wifi settings", Exp("settings", value = "wifi"))
        c("set volume to 40 percent", Exp("volume_set", value = "40"))
        c("good night", Exp("routine_run", target = "good night"))
        c("look at this", Exp("look"))
        c("i spent 250 on lunch", Exp("expense_add", value = "250", message = "lunch"))
        c("how much did i spend this week", Exp("expense_report", value = "this week"))
        c("track habit meditation", Exp("habit_add", target = "meditation"))
        c("log meditation", Exp("habit_log", target = "meditation"))
        c("add milk and eggs to my shopping list", Exp("list_add", target = "shopping", message = "milk and eggs"))
        c("what's on my shopping list", Exp("list_show", target = "shopping"))
        c("move my dentist meeting to 5 pm", Exp("cal_move", target = "dentist", hour = 17, minute = 0))
        c("cancel my dentist appointment", Exp("cal_cancel", target = "dentist"))
        c("when am i free tomorrow", Exp("cal_free", value = "tomorrow"))
        c("when i say priya i mean priyanka", Exp("fix_add", target = "priya", value = "priyanka"))
        c("run morning brief", Exp("routine_run"))
        c("dictate", Exp("dictate", message = ""))
        c("type hello how are you", Exp("dictate", message = "hello how are you"))
        c("make this shorter", Exp("edit_text", message = "make this shorter"))
        c("rewrite this politely", Exp("edit_text", message = "rewrite this politely"))
        c("summarise this page", Exp("screen_ask", message = "summarise this page"))
        c("add priya sharma to my dictionary", Exp("dict_add", value = "priya sharma"))
        c("open spotify and then play some music", Exp("chain"))
        c("read this link", Exp("read_link", target = ""))
        c("news digest", Exp("news_digest"))
        c("my news topics are cricket and tech", Exp("news_topics", value = "cricket, tech"))
        c("where did i park", Exp("park_where"))
        c("i parked on level 3 near the lift", Exp("park_save", message = "on level 3 near the lift"))
        c("stop translating", Exp("translate_off"))
        c("translate to hindi", Exp("translate_on", value = "hindi"))
        c("translate good morning to french", Exp("translate", message = "good morning", value = "french"))
        c("run good night every day at 10 pm", Exp("routine_schedule", target = "good night", hour = 22, minute = 0))
        c("remind me to drink water every 10 minutes until i say done", Exp("nag", message = "drink water", seconds = 600))
        c("nag me to stretch", Exp("nag", message = "stretch", seconds = 300))
        c("stop reminding me", Exp("nag_stop"))
        c("what is this", Exp("look"))
        c("reply to priya saying i will be late", Exp("reply_msg", target = "priya", message = "i will be late"))
        c("reply saying ok", Exp("reply_msg", message = "ok"))
        c("reply yes", Exp("reply_msg", message = "yes"))
        c("run study mode", Exp("routine_run", target = "study mode"))
        c("create a routine called gym: silence my phone, play lo-fi music on spotify", Exp("routine_save", target = "gym"))
        c("put phone on silent", Exp("ringer", value = "silent"))
        c("make the screen brighter", Exp("brightness", value = "up"))
        c("power menu", Exp("global", value = "power"))
        c("elaborate on that", Exp("elaborate"))
        c("turn on dark theme", Exp("toggle", target = "dark theme", value = "on"))
        c("what's my battery level", Exp("battery"))
        c("what time is it", Exp("time"))
        c("what time is it in tokyo", null)
        c("what's today's date", Exp("date"))
        c("read my notifications", Exp("notifications"))
        c("any new messages", Exp("notifications"))
        c("take a selfie", Exp("camera", value = "selfie"))
        c("open the camera", Exp("camera"))
        // photos (a photo request must never be mistaken for news or an app)
        c("open a photo of the beach", Exp("photo"))
        c("show me yesterday's photos", Exp("photo"))
        c("find photos from goa", Exp("photo"))
        c("show me a photo with 3 people", Exp("photo"))
        // updates
        c("update yourself", Exp("update"))
        c("check for updates", Exp("update"))
        c("are you up to date", Exp("update"))
        // apps, music, places
        c("open YouTube", Exp("open_app", target = "youtube"))
        c("launch instagram", Exp("open_app", target = "instagram"))
        c("play kesariya", Exp("play_music", target = "kesariya"))
        c("play arijit singh on spotify", Exp("play_music", target = "arijit singh", value = "spotify"))
        c("navigate to the airport", Exp("navigate", target = "the airport"))
        c("take me to connaught place", Exp("navigate", target = "connaught place"))
        c("find a pharmacy near me", Exp("maps", target = "a pharmacy near me"))
        c("nearest petrol pump", Exp("maps", target = "petrol pump near me"))
        c("search for best pizza in delhi", Exp("search", target = "best pizza in delhi"))
        c("google quantum computing", Exp("search", target = "quantum computing"))
        // conversation
        c("good morning", Exp("briefing"))
        c("thank you", Exp("thanks"))
        c("hello", Exp("greet"))
        c("hey jarvis", Exp("greet"))
        c("never mind", Exp("noop"))
        c("what can you do", Exp("help"))
        // direct answers & study
        c("who won the world cup in 2011", Exp("web_answer"))
        c("who won yesterday's cricket match", Exp("web_answer"))
        c("what's the latest news", Exp("web_answer"))
        c("quiz me on photosynthesis", Exp("tutor", target = "photosynthesis", value = "quiz"))
        c("flashcards for the periodic table", Exp("tutor", target = "the periodic table", value = "flashcards"))
        c("summarize the french revolution", Exp("tutor", value = "summary"))
        c("explain black holes to me like I'm ten", Exp("tutor", value = "explain"))
        // must fall through to the AI
        c("tell me a joke", null)
    }

    private fun matches(p: Plan?, e: Exp?): String? {
        if (e == null) return if (p == null) null else "expected no local match but got ${p.action}"
        if (p == null) return "expected ${e.action} but got no match"
        fun bad(n: String, got: Any?, want: Any?) = "$n: got <$got> want <$want>"
        if (p.action != e.action) return bad("action", p.action, e.action)
        if (e.target != null && p.target != e.target) return bad("target", p.target, e.target)
        if (e.message != null && p.message != e.message) return bad("message", p.message, e.message)
        if (e.hour != null && p.hour != e.hour) return bad("hour", p.hour, e.hour)
        if (e.minute != null && p.minute != e.minute) return bad("minute", p.minute, e.minute)
        if (e.seconds != null && p.seconds != e.seconds) return bad("seconds", p.seconds, e.seconds)
        if (e.value != null && p.value != e.value) return bad("value", p.value, e.value)
        if (e.replyHas != null && !p.reply.contains(e.replyHas)) return bad("reply", p.reply, "contains '${e.replyHas}'")
        return null
    }

    /** Returns a list of human-readable failures (empty = all good). */
    fun run(): List<String> {
        val fails = ArrayList<String>()
        for ((phrase, exp) in cases) {
            val got = try { LocalParser.parse(phrase, NOW) } catch (t: Throwable) { fails.add("\"$phrase\" CRASHED: $t"); continue }
            matches(got, exp)?.let { fails.add("\"$phrase\" -> $it") }
        }
        fun f(name: String, ok: Boolean) { if (!ok) fails.add("check: $name") }

        // follow-up answers to Jarvis's questions
        f("sms message", LocalParser.complete(Plan("sms", target = "rahul"), "see you at six", NOW)?.message == "see you at six")
        f("call who", LocalParser.complete(Plan("call"), "mom", NOW)?.target == "mom")
        f("alarm time", LocalParser.complete(Plan("alarm"), "6 30 am", NOW)?.let { it.hour == 6 && it.minute == 30 } == true)
        f("timer length", LocalParser.complete(Plan("timer"), "ten minutes", NOW)?.seconds == 600)
        f("reminder text", LocalParser.complete(Plan("reminder"), "call the bank", NOW)?.message == "call the bank")
        f("reminder time", LocalParser.complete(Plan("reminder", message = "x"), "in five minutes", NOW)?.seconds == 300)
        f("cancel pending", LocalParser.complete(Plan("sms", target = "a"), "never mind", NOW)?.action == "noop")
        f("unusable alarm answer", LocalParser.complete(Plan("alarm"), "banana", NOW) == null)

        // calculator and clock edge cases
        f("calc precedence", Calc.fromSpeech("2 plus 3 times 4") == 14.0)
        f("calc parens", Calc.fromSpeech("(2+3)*4") == 20.0)
        f("calc bad input", Calc.fromSpeech("hello world") == null)
        f("calc div zero", Calc.fromSpeech("5 / 0") == null)
        f("format whole", Calc.format(8.0) == "8")
        f("format decimal", Calc.format(12.5) == "12.5")
        f("duration none", LocalParser.parseDuration("banana") == null)
        f("clock 24h", LocalParser.parseClock("at 18:05", NOW) == (18 to 5))
        f("next occurrence late night", LocalParser.nextOccurrence(6, 0, 23 * 60) == (6 to 0))
        f("next occurrence morning", LocalParser.nextOccurrence(7, 0, 7 * 60 + 2) == (19 to 0))

        // contact / app name matching
        f("alias mum", Match.score(Match.norm("Mom"), "mum") >= 75)
        f("alias amma", Match.score(Match.norm("Amma"), "mother") >= 75)
        f("fuzzy name", Match.score(Match.norm("Rahul Sharma"), "rahil") > 0)
        f("no false match", Match.score(Match.norm("Priya"), "rahul") == 0)

        // photo requests (today = Thursday 15 Oct 2026)
        val today = LocalDate.of(2026, 10, 15)
        val z = ZoneOffset.UTC
        fun pq(s: String) = PhotoQuery.parse(s, today, z)
        fun ms(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).atStartOfDay(z).toInstant().toEpochMilli()
        f("photo plain", pq("open a photo")?.let { !it.hasFilters && !it.hasDate } == true)
        f("photo yesterday", pq("show me photos from yesterday")?.let { it.fromMs == ms(2026, 10, 14) && it.toMs == ms(2026, 10, 15) && it.place.isEmpty() } == true)
        f("photo yesterday's", pq("show me yesterday's photos")?.fromMs == ms(2026, 10, 14))
        f("photo scene", pq("open a photo of the beach")?.let { it.scenes == listOf("beach") && it.place.isEmpty() } == true)
        f("photo scene plural", pq("show me pictures of mountains")?.scenes == listOf("mountain"))
        f("photo place", pq("find photos from goa")?.let { it.place == "goa" && it.scenes.isEmpty() } == true)
        f("photo this month", pq("show me photos from october")?.let { it.fromMs == ms(2026, 10, 1) && it.toMs == ms(2026, 11, 1) } == true)
        f("photo past month", pq("open photos from december")?.let { it.fromMs == ms(2025, 12, 1) && it.toMs == ms(2026, 1, 1) } == true)
        f("photo people", pq("show me a photo with 3 people")?.minPeople == 3)
        f("photo people words", pq("open a photo of three friends in goa")?.let { it.minPeople == 3 && it.place == "goa" } == true)
        f("photo group", pq("open a group photo")?.minPeople == 2)
        f("photo combo", pq("show photos of sunset in goa last week")?.let {
            it.scenes == listOf("sunset") && it.place == "goa" && it.fromMs == ms(2026, 10, 5) && it.toMs == ms(2026, 10, 12)
        } == true)
        f("photo screenshot", pq("open my last screenshot")?.screenshots == true)
        f("photo not for my mom", pq("open a photo of my mom")?.place == "")
        f("camera is not photo search", pq("take a photo") == null && pq("open the camera") == null)
        return fails
    }
}
