package com.jarvis.assistant.core

import android.Manifest
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.PhoneNumberUtils
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.jarvis.assistant.service.JarvisNotificationListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Turns a [Plan] into real things happening on the phone, and returns what Jarvis should say. */
object Actions {
    private var torchOn = false

    suspend fun run(ctx: Context, p: Plan): Outcome = when (p.action.lowercase()) {
        "call" -> call(ctx, p)
        "sms" -> sms(ctx, p)
        "alarm" -> alarm(ctx, p)
        "alarm_cancel" -> done(launch(ctx, Intent(AlarmClock.ACTION_SHOW_ALARMS)), "Opening your alarms so you can switch it off.", "I couldn't open the alarms.")
        "timer" -> timer(ctx, p)
        "reminder" -> reminder(ctx, p)
        "open_app" -> openApp(ctx, p)
        "play_music" -> playMusic(ctx, p)
        "media" -> media(ctx, p)
        "navigate" -> navigate(ctx, p)
        "maps" -> maps(ctx, p)
        "search" -> search(ctx, p)
        "flashlight" -> flashlight(ctx, p)
        "volume" -> volume(ctx, p)
        "settings" -> settings(ctx, p)
        "volume_set" -> volumeSet(ctx, p)
        "ringer" -> ringer(ctx, p)
        "brightness" -> brightness(ctx, p)
        "camera" -> camera(ctx, p)
        "photo" -> photo(ctx, p)
        "update" -> Outcome(Updater.check(ctx, true))
        "weather" -> weather(p)
        "currency" -> currency(p)
        "note_add" -> noteAdd(p)
        "note_read" -> noteRead()
        "note_clear" -> { Prefs.clearNotes(); Outcome("All your notes are cleared.") }
        "notifications" -> notifications(ctx)
        "briefing" -> briefing(ctx)
        "look" -> done(
            launch(ctx, Intent(ctx, com.jarvis.assistant.MainActivity::class.java).putExtra("look", true)),
            "Opening the camera. Take a photo of it and I'll tell you what I see.", "I couldn't open the camera."
        )
        "reply_msg" -> replyMsg(ctx, p)
        "reply_send" -> replySend(ctx, p)
        "read_link" -> withContext(Dispatchers.IO) {
            var url = p.target.trim()
            if (url.isBlank()) {
                val clip = try {
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
                } catch (e: Exception) { "" }
                url = Regex("https?://\\S+").find(clip)?.value.orEmpty()
            }
            if (url.isBlank()) Outcome("I don't see a link. Copy one, then say read this link.")
            else info(Brain.readArticle(url))
        }
        "news_topics" -> { Prefs.newsTopics = p.value; Outcome("Okay. Your news topics are ${p.value}. Say news digest any time.") }
        "news_digest" -> withContext(Dispatchers.IO) {
            val topics = Prefs.newsTopics.split(',', ';').map { it.trim() }.filter { it.isNotBlank() }.take(4)
            val parts = if (topics.isEmpty()) Fallback.headlines("", 4).let { if (it.isEmpty()) emptyList() else listOf("Top headlines. " + it.joinToString(". ")) }
                else topics.mapNotNull { t -> Fallback.headlines(t, 2).takeIf { it.isNotEmpty() }?.let { "$t. " + it.joinToString(". ") } }
            if (parts.isEmpty()) Outcome("I couldn't get the news right now.")
            else info(("Here's your news. " + parts.joinToString(". ")).replace("..", ".") + (if (topics.isEmpty()) " Say my news topics are, and list them, to personalise this." else ""))
        }
        "translate" -> withContext(Dispatchers.IO) { info(Brain.translate(p.message, p.value.ifBlank { "English" })) }
        "translate_on" -> { Prefs.translateTo = p.value; Outcome("Translation mode on. Say anything and I'll say it in ${p.value}. Say stop translating to leave it.") }
        "translate_off" -> { Prefs.translateTo = ""; Outcome("Translation mode off.") }
        "park_save" -> {
            if (p.message.isBlank()) ask("Where did you park?", p)
            else { Prefs.parking = p.message + "\u001E" + System.currentTimeMillis(); Outcome("Got it. You parked ${p.message}.") }
        }
        "park_where" -> {
            val q = Prefs.parking.split('\u001E')
            if (q.size < 2) Outcome("You haven't told me where you parked yet.")
            else {
                val mins = ((System.currentTimeMillis() - (q[1].toLongOrNull() ?: 0L)) / 60000).toInt()
                val ago = when { mins < 2 -> "just now"; mins < 90 -> "$mins minutes ago"; mins < 36 * 60 -> "${mins / 60} hours ago"; else -> "${mins / 1440} days ago" }
                Outcome("You parked ${q[0]}, $ago.")
            }
        }
        "routine_run" -> routineRun(ctx, p)
        "routine_schedule" -> {
            val r = Routines.find(p.target)
            if (r == null || p.hour !in 0..23) Outcome("I don't have a routine called ${p.target}.")
            else { Scheduler.add(ctx, Scheduler.Sched(r.name, p.hour, p.minute)); Outcome("Okay. ${r.name} will run by itself every day at ${clockLabel(p.hour, p.minute)}.") }
        }
        "routine_unschedule" -> Outcome(if (Scheduler.remove(ctx, p.target)) "Done. ${p.target} won't run by itself any more." else "I don't have ${p.target} scheduled.")
        "routine_scheduled" -> info(Scheduler.list().let { l -> if (l.isEmpty()) "Nothing is scheduled yet." else "Scheduled: " + l.joinToString(". ") { "${it.name} at ${clockLabel(it.hour, it.minute)}" } + "." })
        "nag" -> {
            val every = ((p.seconds / 60).takeIf { it > 0 } ?: 5).coerceIn(1, 180)
            if (p.message.isBlank()) ask("What should I keep reminding you about?", p)
            else { Scheduler.startNag(ctx, p.message, every, every); Outcome("Okay. I'll remind you to ${p.message} every ${if (every == 1) "minute" else "$every minutes"} until you say done.") }
        }
        "nag_stop" -> Outcome(if (Scheduler.stopNags(ctx)) "Good. I've stopped the reminders." else "No reminders are running.")
        "routine_save" -> routineSave(p)
        "routine_list" -> info(Routines.all().joinToString(". ") { it.name }.let { "Your routines are: $it. Say run, then the name." })
        "routine_delete" -> routineDelete(p)
        "phone_do" -> withContext(Dispatchers.Default) { Outcome(PhoneAgent.run(ctx, p.target.ifBlank { p.message })) }
        "toggle" -> toggle(ctx, p)
        "global" -> globalAction(ctx, p)
        "screen_read" -> withContext(Dispatchers.IO) { screenRead() }
        "email" -> email(ctx, p)
        "remember" -> { Prefs.addFact(p.message.ifBlank { p.target }); Outcome("Noted. I'll remember that.") }
        "forget_all" -> { Prefs.clearFacts(); Outcome("Done. I've forgotten everything you asked me to remember.") }
        "memory_read" -> info(Prefs.facts().let { if (it.isEmpty()) "You haven't asked me to remember anything yet." else "I remember: " + it.joinToString(". ") + "." })
        "shop" -> shop(ctx, p)
        "install_app" -> installApp(ctx, p)
        "calendar" -> calendar(ctx, p)
        "agenda" -> agenda(ctx, p)
        "web_answer" -> withContext(Dispatchers.IO) { info(Brain.webAnswer(p.target.ifBlank { p.message })) }
        "elaborate" -> withContext(Dispatchers.IO) {
            val t = p.target.ifBlank { p.message }
            info(if (Regex("(?i)\\b(chapter|unit|lesson|syllabus|textbook|ncert|class \\d+|grade \\d+)\\b").containsMatchIn(t)) Brain.tutor("explain", t, false) else Brain.elaborate(t))
        }
        "tutor" -> withContext(Dispatchers.IO) { info(Brain.tutor(p.value, p.target, true)) }
        "time" -> info("It's " + LocalTime.now().format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())) + ".")
        "date" -> info("Today is " + LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault())) + ".")
        "battery" -> info("Your battery is at ${battery(ctx)} percent.")
        "greet" -> Outcome("Yes, ${Prefs.callMe}?", keepOpen = true)
        "thanks" -> Outcome("Anytime, ${Prefs.callMe}.")
        "noop" -> Outcome("")
        "about" -> info("I'm Jarvis, your personal assistant. Ask me anything or tell me what to do.")
        "help" -> info("I can call, message, set alarms, timers and reminders, take notes, check the weather, do maths, find your photos, read your notifications, play music and more. Open the Skills tab to see everything.")
        else -> Outcome(p.reply.ifBlank { "I'm not sure how to help with that." }, keepOpen = true)
    }

    // ------------------------------------------------------------------ helpers

    private fun info(s: String) = Outcome(s, keepOpen = true)
    private fun done(ok: Boolean, good: String, bad: String) = Outcome(if (ok) good else bad)
    private fun ask(q: String, p: Plan) = Outcome(q, awaiting = p, keepOpen = true)

    private fun granted(ctx: Context, perm: String) =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    private fun launch(ctx: Context, i: Intent): Boolean = try {
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        false
    }

    private fun battery(ctx: Context): Int =
        (ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    // ------------------------------------------------------------------ calls & messages

    private data class Hit(val name: String, val number: String)

    private suspend fun resolve(ctx: Context, target: String): Hit? {
        val digits = target.filter { it.isDigit() || it == '+' }
        if (digits.count { it.isDigit() } >= 5 && target.none { it.isLetter() }) return Hit(target, digits)
        return findContact(ctx, target)
    }

    private suspend fun call(ctx: Context, p: Plan): Outcome {
        val t = p.target.trim()
        if (t.isEmpty()) return ask("Who should I call?", p)
        if (!granted(ctx, Manifest.permission.READ_CONTACTS) && t.any { it.isLetter() }) {
            return Outcome("I need contacts permission to find $t. Please grant it in setup.")
        }
        val hit = resolve(ctx, t) ?: return Outcome("I couldn't find $t in your contacts.")
        val action = if (granted(ctx, Manifest.permission.CALL_PHONE)) Intent.ACTION_CALL else Intent.ACTION_DIAL
        val ok = launch(ctx, Intent(action, Uri.parse("tel:" + Uri.encode(hit.number))))
        return done(ok, p.reply.ifBlank { "Calling ${hit.name}." }, "I couldn't place the call.")
    }

    private suspend fun sms(ctx: Context, p: Plan): Outcome {
        val t = p.target.trim()
        if (t.isEmpty()) return ask("Who should I message?", p)
        if (p.value.equals("whatsapp_group", true)) {
            if (p.message.isBlank()) return ask("What should I say to the $t group?", p)
            val auto = A11y.enabled(ctx)
            if (auto) WhatsAppSender.armGroup(t)
            val share = Intent(Intent.ACTION_SEND).setType("text/plain").setPackage("com.whatsapp").putExtra(Intent.EXTRA_TEXT, p.message)
            val line = if (auto) "Sending your message to the $t group." else "Pick the $t group and hit send. Turn on Accessibility in setup and I'll do it for you."
            return done(launch(ctx, share), line, "I couldn't open WhatsApp.")
        }
        if (!granted(ctx, Manifest.permission.READ_CONTACTS) && t.any { it.isLetter() }) {
            return Outcome("I need contacts permission to find $t. Please grant it in setup.")
        }
        val hit = resolve(ctx, t) ?: return Outcome("I couldn't find $t in your contacts.")
        if (p.message.isBlank()) return ask("What should I say to ${hit.name}?", p.copy(target = hit.name))
        if (p.value.equals("whatsapp", true)) {
            val e164 = PhoneNumberUtils.formatNumberToE164(hit.number, Locale.getDefault().country) ?: hit.number
            val url = "https://wa.me/" + e164.filter { it.isDigit() } + "?text=" + Uri.encode(p.message)
            val auto = A11y.enabled(ctx)
            if (auto) WhatsAppSender.arm()
            val line = if (auto) "Sending your WhatsApp message to ${hit.name}." else "WhatsApp message to ${hit.name} is ready. Just hit send."
            return done(launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(url))), line, "I couldn't open WhatsApp.")
        }
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(hit.number))).putExtra("sms_body", p.message)
        return done(launch(ctx, i), p.reply.ifBlank { "Message to ${hit.name} is ready. Just hit send." }, "I couldn't open your messaging app.")
    }

    private suspend fun findContact(ctx: Context, query: String): Hit? = withContext(Dispatchers.IO) {
        val q = Match.norm(query)
        var best: Hit? = null
        var bestScore = 0
        try {
            ctx.contentResolver.query(
                Phone.CONTENT_URI,
                arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER, Phone.TYPE, Phone.IS_PRIMARY),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    val number = c.getString(1) ?: continue
                    var s = Match.score(Match.norm(name), q)
                    if (s == 0) continue
                    if (c.getInt(2) == Phone.TYPE_MOBILE) s += 2
                    if (c.getInt(3) != 0) s += 1
                    if (s > bestScore) {
                        bestScore = s
                        best = Hit(name, number)
                    }
                }
            }
        } catch (e: SecurityException) {
            return@withContext null
        }
        best
    }

    // ------------------------------------------------------------------ alarms, timers, reminders

    private fun clockLabel(h: Int, m: Int): String {
        val h12 = if (h % 12 == 0) 12 else h % 12
        return String.format(Locale.getDefault(), "%d:%02d %s", h12, m, if (h < 12) "AM" else "PM")
    }

    private fun setAlarm(ctx: Context, h: Int, m: Int, label: String): Boolean = launch(
        ctx,
        Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h)
            .putExtra(AlarmClock.EXTRA_MINUTES, m.coerceIn(0, 59))
            .putExtra(AlarmClock.EXTRA_MESSAGE, label)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
    )

    private fun setTimer(ctx: Context, secs: Int, label: String): Boolean = launch(
        ctx,
        Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, secs)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
    )

    private fun alarm(ctx: Context, p: Plan): Outcome {
        if (p.hour !in 0..23) return ask("What time should I set the alarm for?", p)
        return done(
            setAlarm(ctx, p.hour, p.minute, p.message.ifBlank { "Jarvis alarm" }),
            p.reply.ifBlank { "Alarm set for ${clockLabel(p.hour, p.minute)}." }, "I couldn't set the alarm."
        )
    }

    private fun timer(ctx: Context, p: Plan): Outcome {
        if (p.seconds <= 0) return ask("For how long should I set the timer?", p)
        val label = if (p.message == "alarm") "Jarvis alarm" else p.message
        return done(setTimer(ctx, p.seconds, label), p.reply.ifBlank { "Timer set for ${human(p.seconds)}." }, "I couldn't set the timer.")
    }

    private fun reminder(ctx: Context, p: Plan): Outcome {
        if (p.message.isBlank()) return ask("What should I remind you about?", p)
        if (p.seconds > 0) {
            return done(
                setTimer(ctx, p.seconds, "Reminder: ${p.message}"),
                p.reply.ifBlank { "Okay, I'll remind you to ${p.message} in ${human(p.seconds)}." }, "I couldn't set the reminder."
            )
        }
        if (p.hour in 0..23) {
            return done(
                setAlarm(ctx, p.hour, p.minute, "Reminder: ${p.message}"),
                p.reply.ifBlank { "Okay, I'll remind you to ${p.message} at ${clockLabel(p.hour, p.minute)}." }, "I couldn't set the reminder."
            )
        }
        return ask("When should I remind you?", p)
    }

    private fun human(s: Int): String {
        val parts = buildList {
            val h = s / 3600
            val m = (s % 3600) / 60
            val sec = s % 60
            if (h > 0) add("$h hour" + if (h > 1) "s" else "")
            if (m > 0) add("$m minute" + if (m > 1) "s" else "")
            if (sec > 0) add("$sec second" + if (sec > 1) "s" else "")
        }
        return parts.joinToString(" ")
    }

    // ------------------------------------------------------------------ apps, media, web

    private fun openApp(ctx: Context, p: Plan): Outcome {
        val q = Match.norm(p.target)
        if (q.isEmpty()) return ask("Which app should I open?", p)
        val pm = ctx.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        var best: Pair<String, String>? = null
        var bestScore = 0
        for (ri in apps) {
            val label = ri.loadLabel(pm).toString()
            val s = Match.score(Match.norm(label), q)
            if (s > bestScore) { bestScore = s; best = label to ri.activityInfo.packageName }
        }
        val (label, pkg) = best ?: return Outcome("I couldn't find an app called ${p.target}.")
        val i = pm.getLaunchIntentForPackage(pkg) ?: return Outcome("I couldn't open $label.")
        return done(launch(ctx, i), p.reply.ifBlank { "Opening $label." }, "I couldn't open $label.")
    }

    /** Opens Jarvis so Android can ask for the permissions that are still missing. */
    private fun needPerm(ctx: Context, say: String): Outcome {
        launch(ctx, Intent(ctx, com.jarvis.assistant.MainActivity::class.java).putExtra("askPerms", true))
        return Outcome(say)
    }

    private suspend fun toggle(ctx: Context, p: Plan): Outcome {
        val thing = p.target.trim()
        val state = if (p.value.equals("off", true)) "off" else "on"
        if (com.jarvis.assistant.system.JarvisAccessibilityService.instance == null) {
            val v = when {
                "wi" in thing -> "wifi"
                "blue" in thing -> "bluetooth"
                "air" in thing || "flight" in thing -> "airplane"
                "hotspot" in thing -> "hotspot"
                "location" in thing || "gps" in thing -> "location"
                else -> ""
            }
            val r = settings(ctx, p.copy(value = v))
            return Outcome("Android won't let apps flip $thing by themselves. Switch Accessibility on for Jarvis in setup and I'll do it for you. " + r.reply)
        }
        com.jarvis.assistant.system.JarvisAccessibilityService.instance?.quickToggle(thing, state == "on")?.let { return Outcome(it) }
        val goal = "Turn $thing $state. Pull down the quick settings panel (use the quick_settings action), tap the $thing tile only if it is not already $state, " +
            "and check its state afterwards. If the tile isn't there, open Settings and find it. Finish with done and say what it is now."
        return Outcome(PhoneAgent.run(ctx, goal))
    }

    private fun globalAction(ctx: Context, p: Plan): Outcome {
        val svc = com.jarvis.assistant.system.JarvisAccessibilityService.instance
            ?: return Outcome("I need Accessibility switched on in setup for that.")
        return if (svc.global(p.value)) Outcome(when (p.value) {
            "screenshot" -> "Screenshot taken."
            "lock" -> "Locking."
            else -> ""
        }) else Outcome("Android didn't let me do that one.")
    }

    private suspend fun screenRead(): Outcome {
        val svc = com.jarvis.assistant.system.JarvisAccessibilityService.instance
            ?: return Outcome("I need Accessibility switched on in setup to see your screen.")
        val (pkg, items) = svc.items()
        if (items.isEmpty()) return Outcome("I can't read anything on this screen.")
        val text = items.joinToString("\n") { it.label }.take(4000)
        return info(Brain.screenSummary(pkg, text))
    }

    private fun email(ctx: Context, p: Plan): Outcome {
        val who = p.target.trim()
        if (who.isEmpty()) return ask("Who should I email?", p)
        var address = who.takeIf { it.contains("@") }
        if (address == null && granted(ctx, Manifest.permission.READ_CONTACTS)) {
            var best = 0
            try {
                val q = Match.norm(who)
                ctx.contentResolver.query(
                    android.provider.ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                    arrayOf(android.provider.ContactsContract.CommonDataKinds.Email.DISPLAY_NAME, android.provider.ContactsContract.CommonDataKinds.Email.ADDRESS),
                    null, null, null
                )?.use { c ->
                    while (c.moveToNext()) {
                        val s = Match.score(Match.norm(c.getString(0).orEmpty()), q)
                        if (s > best) { best = s; address = c.getString(1) }
                    }
                }
            } catch (_: Exception) {
            }
        }
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + (address ?: "")))
            .putExtra(Intent.EXTRA_TEXT, p.message)
        return done(launch(ctx, i), if (address != null) "Email to $who is ready. Just hit send." else "Email is ready. I couldn't find an address for $who, so add it.", "I couldn't open your email app.")
    }

    private fun shop(ctx: Context, p: Plan): Outcome {
        val q = p.target.trim()
        if (q.isEmpty()) return ask("What should I look for?", p)
        val site = if (p.value.equals("flipkart", true)) "https://www.flipkart.com/search?q=" else "https://www.amazon.in/s?k="
        return done(launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(site + Uri.encode(q)))), "Here's $q.", "I couldn't open the store.")
    }

    private fun installApp(ctx: Context, p: Plan): Outcome {
        val q = p.target.trim()
        if (q.isEmpty()) return ask("Which app should I get?", p)
        val ok = launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=" + Uri.encode(q) + "&c=apps")).setPackage("com.android.vending")) ||
            launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?c=apps&q=" + Uri.encode(q))))
        return done(ok, "Opening the Play Store for $q. Tap Install.", "I couldn't open the Play Store.")
    }

    private fun calPerm(ctx: Context) =
        granted(ctx, Manifest.permission.READ_CALENDAR) && granted(ctx, Manifest.permission.WRITE_CALENDAR)

    /** The calendar to write into: a writable one, the main Google one first. */
    private fun writableCalendar(ctx: Context): Long? {
        var best: Long? = null
        var bestScore = -1
        try {
            ctx.contentResolver.query(
                android.provider.CalendarContract.Calendars.CONTENT_URI, arrayOf(android.provider.CalendarContract.Calendars._ID, android.provider.CalendarContract.Calendars.IS_PRIMARY, android.provider.CalendarContract.Calendars.ACCOUNT_TYPE, android.provider.CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, android.provider.CalendarContract.Calendars.VISIBLE),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    if (c.getInt(3) < 500) continue
                    var s = 1
                    if (c.getInt(1) == 1) s += 4
                    if (c.getString(2) == "com.google") s += 2
                    if (c.getInt(4) == 1) s += 1
                    if (s > bestScore) { bestScore = s; best = c.getLong(0) }
                }
            }
        } catch (e: Exception) {
            return null
        }
        return best
    }

    /** Plan: message = title, hour/minute = start, value = today | tomorrow | yyyy-MM-dd, seconds = length. Saves straight into the calendar. */
    private fun calendar(ctx: Context, p: Plan): Outcome {
        val title = p.message.trim().ifBlank { "Blocked time" }
        val c = java.util.Calendar.getInstance()
        val v = p.value.trim().lowercase()
        if (v == "tomorrow") c.add(java.util.Calendar.DAY_OF_YEAR, 1)
        else Regex("(\\d{4})-(\\d{2})-(\\d{2})").find(v)?.let {
            c.set(it.groupValues[1].toInt(), it.groupValues[2].toInt() - 1, it.groupValues[3].toInt())
        }
        if (p.hour in 0..23) {
            c.set(java.util.Calendar.HOUR_OF_DAY, p.hour); c.set(java.util.Calendar.MINUTE, p.minute.coerceIn(0, 59))
        } else {
            c.add(java.util.Calendar.HOUR_OF_DAY, 1); c.set(java.util.Calendar.MINUTE, 0)
        }
        c.set(java.util.Calendar.SECOND, 0)
        val start = c.timeInMillis
        val end = start + (if (p.seconds > 0) p.seconds * 1000L else 3_600_000L)
        val whenText = java.text.SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault()).format(java.util.Date(start))

        if (calPerm(ctx)) {
            val id = writableCalendar(ctx)
            if (id != null) {
                try {
                    val values = android.content.ContentValues().apply {
                        put(android.provider.CalendarContract.Events.CALENDAR_ID, id)
                        put(android.provider.CalendarContract.Events.TITLE, title)
                        put(android.provider.CalendarContract.Events.DTSTART, start)
                        put(android.provider.CalendarContract.Events.DTEND, end)
                        put(android.provider.CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
                    }
                    if (ctx.contentResolver.insert(android.provider.CalendarContract.Events.CONTENT_URI, values) != null) {
                        return Outcome(p.reply.ifBlank { "Done. \"$title\" is in your calendar for $whenText." })
                    }
                } catch (e: Exception) {
                    // fall through to the calendar app
                }
            }
        }
        val i = Intent(Intent.ACTION_INSERT).setData(android.provider.CalendarContract.Events.CONTENT_URI)
            .putExtra(android.provider.CalendarContract.Events.TITLE, title)
            .putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
            .putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, end)
        if (!calPerm(ctx)) {
            needPerm(ctx, "")
            return Outcome("I need calendar access to save that myself. Tap Allow on the screen that just opened, then ask me again.")
        }
        val tip = ""
        return done(launch(ctx, i), "Your calendar is open with \"$title\" for $whenText. Tap Save.$tip", "I couldn't open your calendar.")
    }

    /** Reads the day's events aloud. value = today | tomorrow. */
    private fun agenda(ctx: Context, p: Plan): Outcome {
        if (!calPerm(ctx)) return needPerm(ctx, "I need calendar access first. Tap Allow on the screen that just opened, then ask me again.")
        val c = java.util.Calendar.getInstance()
        if (p.value.equals("tomorrow", true)) c.add(java.util.Calendar.DAY_OF_YEAR, 1)
        c.set(java.util.Calendar.HOUR_OF_DAY, 0); c.set(java.util.Calendar.MINUTE, 0); c.set(java.util.Calendar.SECOND, 0)
        val from = c.timeInMillis
        val to = from + 86_400_000L
        val uri = android.provider.CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            android.content.ContentUris.appendId(it, from); android.content.ContentUris.appendId(it, to)
        }.build()
        val fmt = java.text.SimpleDateFormat("h:mm a", Locale.getDefault())
        val items = ArrayList<String>()
        try {
            ctx.contentResolver.query(
                uri,
                arrayOf(android.provider.CalendarContract.Instances.TITLE, android.provider.CalendarContract.Instances.BEGIN, android.provider.CalendarContract.Instances.ALL_DAY),
                null, null, android.provider.CalendarContract.Instances.BEGIN + " ASC"
            )?.use { q ->
                while (q.moveToNext() && items.size < 8) {
                    val t = q.getString(0).orEmpty().ifBlank { "Busy" }
                    items.add(if (q.getInt(2) == 1) "$t, all day" else t + " at " + fmt.format(java.util.Date(q.getLong(1))))
                }
            }
        } catch (e: Exception) {
            return Outcome("I couldn't read your calendar.")
        }
        val day = if (p.value.equals("tomorrow", true)) "tomorrow" else "today"
        if (items.isEmpty()) return info("You have nothing on your calendar $day.")
        return info("You have ${items.size} thing${if (items.size > 1) "s" else ""} $day. " + items.joinToString(". ") + ".")
    }

    private fun pkgByLabel(ctx: Context, word: String): String? {
        val pm = ctx.packageManager
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .firstOrNull { it.loadLabel(pm).toString().lowercase().contains(word) }?.activityInfo?.packageName
    }

    private fun installed(ctx: Context, pkg: String) = try {
        ctx.packageManager.getPackageInfo(pkg, 0); true
    } catch (e: Exception) {
        false
    }

    private fun playMusic(ctx: Context, p: Plan): Outcome {
        val q = p.target.trim()
        if (q.isEmpty()) return ask("What should I play?", p)
        val where = p.value.lowercase().trim()
        val platform = when {
            "spotify" in where -> "spotify"
            "amazon" in where || "prime" in where -> "amazon"
            "joytify" in where || "joy" in where -> "joytify"
            "youtube" in where || "you tube" in where -> "youtube"
            else -> ""
        }
        if (platform.isEmpty()) {
            return ask("Which app should I use: Spotify, Amazon Music or Joytify?", p.copy(value = "?pick"))
        }
        val youtube = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q)))
        if (platform == "youtube") return done(launch(ctx, youtube), p.reply.ifBlank { "Searching YouTube for $q." }, "I couldn't open YouTube.")

        val (pkg, name) = when (platform) {
            "spotify" -> "com.spotify.music" to "Spotify"
            "amazon" -> "com.amazon.mp3" to "Amazon Music"
            else -> (pkgByLabel(ctx, "joytify") ?: "") to "Joytify"
        }
        if (pkg.isEmpty() || !installed(ctx, pkg)) return Outcome("I couldn't find $name on this phone.")

        // "X by Y" is a structured song search; anything else is a plain query
        val by = Regex("^(.+?)\\s+by\\s+(.+)$", RegexOption.IGNORE_CASE).find(q)
        val search = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(pkg).putExtra(SearchManager.QUERY, q)
        if (by != null) {
            search.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)
                .putExtra(MediaStore.EXTRA_MEDIA_TITLE, by.groupValues[1].trim())
                .putExtra(MediaStore.EXTRA_MEDIA_ARTIST, by.groupValues[2].trim())
        } else {
            search.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        }
        var ok = launch(ctx, search)
        if (!ok && platform == "spotify") ok = launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(q))).setPackage(pkg))
        if (!ok) ok = ctx.packageManager.getLaunchIntentForPackage(pkg)?.let { launch(ctx, it) } ?: false
        if (ok) makeItPlay(ctx, pkg, q)
        val canPress = com.jarvis.assistant.system.JarvisAccessibilityService.instance != null || JarvisNotificationListener.connected
        val hint = if (ok && !canPress) " If it only shows search results, switch on Jarvis in Accessibility settings and I'll press play for you." else ""
        return done(ok, p.reply.ifBlank { "Playing $q on $name." } + hint, "I couldn't open $name.")
    }

    /**
     * Music apps often open on the song but wait to be told to start, or land on search results.
     * A few seconds later: ask the app's own media session to play the search, else tap the top result and press play.
     */
    private fun makeItPlay(ctx: Context, pkg: String, q: String) {
        Thread {
            try {
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                Thread.sleep(3800)
                if (am.isMusicActive) return@Thread
                if (viaSession(ctx, pkg, q)) {
                    Thread.sleep(2500)
                    if (am.isMusicActive) return@Thread
                }
                val svc = com.jarvis.assistant.system.JarvisAccessibilityService.instance
                if (svc != null) {
                    for (attempt in 0 until 3) {
                        if (am.isMusicActive) return@Thread
                        if (svc.tapFirstResult(pkg, q)) {
                            Thread.sleep(2600)
                            if (!am.isMusicActive) svc.tapPlay(pkg)
                            Thread.sleep(1200)
                            break
                        }
                        Thread.sleep(1500)
                    }
                }
                if (!am.isMusicActive) {
                    am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
                    am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
                }
            } catch (_: Exception) {
            }
        }.start()
    }

    /** Needs notification access (already used for reading notifications aloud); quietly false without it. */
    private fun viaSession(ctx: Context, pkg: String, q: String): Boolean = try {
        val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as android.media.session.MediaSessionManager
        val c = msm.getActiveSessions(android.content.ComponentName(ctx, JarvisNotificationListener::class.java)).firstOrNull { it.packageName == pkg }
        if (c != null) {
            c.transportControls.playFromSearch(q, android.os.Bundle())
            true
        } else false
    } catch (e: Exception) {
        false
    }

    /** Works with any music / video app, no permission needed. */
    private fun media(ctx: Context, p: Plan): Outcome {
        val code = when (p.value) {
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> return Outcome("What should I do with the music?")
        }
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return Outcome(p.reply)
    }

    private fun navigate(ctx: Context, p: Plan): Outcome {
        val q = p.target.trim()
        if (q.isEmpty()) return ask("Where would you like to go?", p)
        val ok = launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(q)))) ||
            launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(q))))
        return done(ok, p.reply.ifBlank { "Navigating to $q." }, "I couldn't open maps.")
    }

    private fun maps(ctx: Context, p: Plan): Outcome {
        val q = p.target.trim()
        if (q.isEmpty()) return ask("What should I look for?", p)
        return done(launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(q)))), p.reply.ifBlank { "Showing $q on the map." }, "I couldn't open maps.")
    }

    private fun search(ctx: Context, p: Plan): Outcome {
        val q = p.target.trim()
        if (q.isEmpty()) return ask("What should I search for?", p)
        val ok = launch(ctx, Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q)) ||
            launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))))
        return done(ok, p.reply.ifBlank { "Searching for $q." }, "I couldn't open the browser.")
    }

    private fun camera(ctx: Context, p: Plan): Outcome {
        val i = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        if (p.value == "selfie") i.putExtra("android.intent.extras.CAMERA_FACING", 1).putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
        return done(launch(ctx, i), if (p.value == "selfie") "Selfie camera is open." else "Camera is open.", "I couldn't open the camera.")
    }

    private suspend fun photo(ctx: Context, p: Plan): Outcome {
        val q = PhotoQuery.parse(p.target) ?: PhotoQuery.parse("open a photo " + p.target)
            ?: return Outcome("Which photo should I open?")
        return PhotoFinder.find(ctx, q)
    }

    // ------------------------------------------------------------------ device controls

    private fun flashlight(ctx: Context, p: Plan): Outcome = try {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        if (id == null) Outcome("This phone doesn't seem to have a flashlight.") else {
            val on = when (p.value.lowercase()) { "on" -> true; "off" -> false; else -> !torchOn }
            cm.setTorchMode(id, on)
            torchOn = on
            Outcome(p.reply.ifBlank { if (on) "Flashlight on." else "Flashlight off." })
        }
    } catch (e: Exception) {
        Outcome("I couldn't control the flashlight.")
    }

    private fun volume(ctx: Context, p: Plan): Outcome {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val flag = AudioManager.FLAG_SHOW_UI
        return when (p.value.lowercase()) {
            "up" -> { repeat(3) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, flag) }; Outcome(p.reply.ifBlank { "Volume up." }) }
            "down" -> { repeat(3) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, flag) }; Outcome(p.reply.ifBlank { "Volume down." }) }
            "mute" -> { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, flag); Outcome(p.reply.ifBlank { "Muted." }) }
            else -> ask("Should I turn the volume up or down?", p)
        }
    }

    private val settingsPages = listOf(
        "wi" to Settings.ACTION_WIFI_SETTINGS,
        "bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS,
        "airplane" to Settings.ACTION_AIRPLANE_MODE_SETTINGS,
        "flight" to Settings.ACTION_AIRPLANE_MODE_SETTINGS,
        "hotspot" to "android.settings.TETHER_SETTINGS",
        "display" to Settings.ACTION_DISPLAY_SETTINGS,
        "brightness" to Settings.ACTION_DISPLAY_SETTINGS,
        "sound" to Settings.ACTION_SOUND_SETTINGS,
        "battery" to Intent.ACTION_POWER_USAGE_SUMMARY,
        "location" to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
        "nfc" to Settings.ACTION_NFC_SETTINGS,
        "storage" to Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
        "date" to Settings.ACTION_DATE_SETTINGS,
        "time" to Settings.ACTION_DATE_SETTINGS,
        "language" to Settings.ACTION_LOCALE_SETTINGS,
        "access" to Settings.ACTION_ACCESSIBILITY_SETTINGS,
        "privacy" to Settings.ACTION_PRIVACY_SETTINGS,
        "security" to Settings.ACTION_SECURITY_SETTINGS,
        "data" to "android.settings.DATA_USAGE_SETTINGS",
        "vpn" to "android.net.vpn.SETTINGS",
        "cast" to Settings.ACTION_CAST_SETTINGS,
        "developer" to Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
        "default" to Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS,
        "keyboard" to Settings.ACTION_INPUT_METHOD_SETTINGS,
        "about" to Settings.ACTION_DEVICE_INFO_SETTINGS,
        "sync" to Settings.ACTION_SYNC_SETTINGS,
        "app" to Settings.ACTION_APPLICATION_SETTINGS,
        "notification" to Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
        "disturb" to Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS,
        "wallpaper" to Intent.ACTION_SET_WALLPAPER
    )

    /** Opens the right Android settings page. (Switching things on and off is done by "toggle".) */
    private fun settings(ctx: Context, p: Plan): Outcome {
        val k = p.value.lowercase().trim()
        val action = settingsPages.firstOrNull { k.contains(it.first) }?.second ?: Settings.ACTION_SETTINGS
        val ok = launch(ctx, Intent(action)) || launch(ctx, Intent(Settings.ACTION_SETTINGS))
        return done(ok, p.reply.ifBlank { "Opening ${p.value.ifBlank { "settings" }} settings." }, "I couldn't open settings.")
    }

    private fun volumeSet(ctx: Context, p: Plan): Outcome {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val pct = p.value.toIntOrNull()?.coerceIn(0, 100) ?: return ask("What volume, from 0 to 100?", p)
        val t = p.target.lowercase()
        val stream = when {
            "ring" in t -> AudioManager.STREAM_RING
            "alarm" in t -> AudioManager.STREAM_ALARM
            "notif" in t -> AudioManager.STREAM_NOTIFICATION
            else -> AudioManager.STREAM_MUSIC
        }
        return try {
            am.setStreamVolume(stream, Math.round(am.getStreamMaxVolume(stream) * pct / 100f), AudioManager.FLAG_SHOW_UI)
            Outcome("Volume at $pct percent.")
        } catch (e: Exception) {
            Outcome("Android wouldn't let me change that volume.")
        }
    }

    private fun ringer(ctx: Context, p: Plan): Outcome {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val v = p.value.lowercase()
        val mode = when {
            v.startsWith("silent") -> AudioManager.RINGER_MODE_SILENT
            v.startsWith("vib") -> AudioManager.RINGER_MODE_VIBRATE
            else -> AudioManager.RINGER_MODE_NORMAL
        }
        return try {
            am.ringerMode = mode
            Outcome(when (mode) {
                AudioManager.RINGER_MODE_SILENT -> "Silent mode on."
                AudioManager.RINGER_MODE_VIBRATE -> "Vibrate mode on."
                else -> "Sound is back on."
            })
        } catch (e: Exception) {
            launch(ctx, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            Outcome("For that Android needs Do Not Disturb access. I opened the page. Switch Jarvis on, then ask me again.")
        }
    }

    private fun brightness(ctx: Context, p: Plan): Outcome {
        if (!Settings.System.canWrite(ctx)) {
            launch(ctx, Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + ctx.packageName)))
            return Outcome("I need permission to change brightness. I opened the page. Switch Jarvis on, then ask me again.")
        }
        return try {
            val cr = ctx.contentResolver
            val cur = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
            val target = when (p.value) {
                "up" -> cur + 70
                "down" -> cur - 70
                else -> (p.value.toIntOrNull() ?: return ask("What brightness, from 0 to 100?", p)) * 255 / 100
            }.coerceIn(5, 255)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, target)
            Outcome("Brightness at ${target * 100 / 255} percent.")
        } catch (e: Exception) {
            Outcome("Android wouldn't let me change the brightness.")
        }
    }

    // ------------------------------------------------------------------ notes, notifications, briefing

    private fun noteAdd(p: Plan): Outcome {
        if (p.message.isBlank()) return ask("What should I note down?", p)
        Prefs.addNote(p.message)
        return Outcome("Noted.")
    }

    private fun noteRead(): Outcome {
        val n = Prefs.notes()
        if (n.isEmpty()) return info("You have no notes.")
        val spoken = n.takeLast(5).mapIndexed { i, s -> "${i + 1}. $s" }.joinToString(". ")
        return info("You have ${n.size} note" + (if (n.size > 1) "s" else "") + ". " + spoken)
    }

    private fun notifications(ctx: Context): Outcome {
        val list = JarvisNotificationListener.summaries(ctx)
        if (list == null) {
            launch(ctx, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            return Outcome("I need notification access first. I've opened the settings page, just switch Jarvis on.")
        }
        if (list.isEmpty()) return info("You have no new notifications.")
        return info("You have ${list.size} recent notification" + (if (list.size > 1) "s" else "") + ". " + list.joinToString(". "))
    }

    private fun replyMsg(ctx: Context, p: Plan): Outcome {
        if (!JarvisNotificationListener.connected) {
            launch(ctx, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            return Outcome("I need notification access to reply. I've opened the page, just switch Jarvis on.")
        }
        val msg = p.message.trim()
        if (msg.isEmpty()) return ask("What should I reply?", p)
        val r = JarvisNotificationListener.latestReplyable(ctx, p.target)
            ?: return Outcome(if (p.target.isBlank()) "I don't see a message I can reply to right now." else "I don't see a message from ${p.target} that I can reply to.")
        return ask("Reply to ${r.sender} on ${r.app}: $msg. Shall I send it?", Plan("reply_send", target = r.sender, message = msg))
    }

    private fun replySend(ctx: Context, p: Plan): Outcome {
        if (p.value != "yes") return Outcome("Okay, I won't send it.")
        val r = JarvisNotificationListener.latestReplyable(ctx, p.target) ?: return Outcome("That message has gone from your notifications, so I couldn't reply.")
        return Outcome(if (JarvisNotificationListener.sendReply(r, p.message)) "Sent." else "I couldn't send that reply.")
    }

    private suspend fun routineRun(ctx: Context, p: Plan): Outcome {
        val r = Routines.find(p.target) ?: return Outcome("I don't have a routine called ${p.target}.")
        val said = ArrayList<String>()
        for (step in r.steps) {
            val plan = (LocalParser.parse(step) ?: try { Brain.plan(step, emptyList()) } catch (e: Exception) { null }) ?: continue
            if (plan.action.startsWith("routine")) continue
            val out = try { run(ctx, plan) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { continue }
            if (out.reply.isNotBlank() && plan.action != "answer") said.add(out.reply)
            kotlinx.coroutines.delay(900)
        }
        return Outcome("Running ${r.name}. " + said.joinToString(" "))
    }

    private fun routineSave(p: Plan): Outcome {
        val steps = Routines.splitSteps(p.message)
        if (p.target.isBlank() || steps.isEmpty()) return Outcome("Tell me the name and the steps, for example: create a routine called gym, silence my phone, play lo-fi music on Spotify.")
        val others = Prefs.routines().filter { it.name.lowercase() != p.target.lowercase() }
        Prefs.saveRoutines(others + Routines.Routine(p.target.trim(), steps))
        return Outcome("Saved the routine ${p.target} with ${steps.size} step" + (if (steps.size == 1) "." else "s.") + " Say run ${p.target} any time.")
    }

    private fun routineDelete(p: Plan): Outcome {
        val before = Prefs.routines()
        val after = before.filter { it.name.lowercase() != p.target.lowercase().trim() }
        if (after.size == before.size) return Outcome("I couldn't find a routine of yours called ${p.target}.")
        Prefs.saveRoutines(after)
        return Outcome("Deleted the routine ${p.target}.")
    }

    private suspend fun briefing(ctx: Context): Outcome {
        val h = LocalTime.now().hour
        val greet = when { h < 12 -> "Good morning"; h < 17 -> "Good afternoon"; else -> "Good evening" }
        val parts = ArrayList<String>()
        parts.add("$greet, ${Prefs.callMe}.")
        parts.add(
            "It's " + LocalTime.now().format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())) +
                " on " + LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault())) + "."
        )
        parts.add("Battery is at ${battery(ctx)} percent.")
        if (Prefs.homeCity.isNotBlank()) {
            val w = weather(Plan("weather", target = Prefs.homeCity)).reply
            if (!w.startsWith("I couldn't") && !w.startsWith("I can't")) parts.add(w)
        }
        val notes = Prefs.notes().size
        if (notes > 0) parts.add("You have $notes saved note" + (if (notes > 1) "s." else "."))
        JarvisNotificationListener.summaries(ctx, 20)?.size?.takeIf { it > 0 }?.let { parts.add("You have $it notification" + (if (it > 1) "s." else ".")) }
        return info(parts.joinToString(" "))
    }

    // ------------------------------------------------------------------ internet helpers (no keys needed)

    private suspend fun weather(p: Plan): Outcome = withContext(Dispatchers.IO) {
        val city = p.target.ifBlank { Prefs.homeCity }
        if (city.isBlank()) return@withContext ask("Which city?", p.copy(action = "weather"))
        try {
            val geo = JSONObject(Net.get("https://geocoding-api.open-meteo.com/v1/search?count=1&name=" + URLEncoder.encode(city, "UTF-8")))
            val r = geo.optJSONArray("results")?.optJSONObject(0) ?: return@withContext Outcome("I couldn't find a place called $city.")
            val fahrenheit = Locale.getDefault().country == "US"
            val url = "https://api.open-meteo.com/v1/forecast?latitude=${r.getDouble("latitude")}&longitude=${r.getDouble("longitude")}" +
                "&current=temperature_2m,apparent_temperature,weather_code&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                "&timezone=auto&forecast_days=1" + if (fahrenheit) "&temperature_unit=fahrenheit" else ""
            val w = JSONObject(Net.get(url))
            val cur = w.getJSONObject("current")
            val day = w.getJSONObject("daily")
            val temp = Math.round(cur.getDouble("temperature_2m")).toInt()
            val feels = Math.round(cur.getDouble("apparent_temperature")).toInt()
            val hi = Math.round(day.getJSONArray("temperature_2m_max").getDouble(0)).toInt()
            val lo = Math.round(day.getJSONArray("temperature_2m_min").getDouble(0)).toInt()
            val rain = day.getJSONArray("precipitation_probability_max").optInt(0)
            info(
                "In ${r.optString("name", city)} it's $temp degrees with ${Wmo.describe(cur.getInt("weather_code"))}, feels like $feels. " +
                    "Today ranges from $lo to $hi, with a $rain percent chance of rain."
            )
        } catch (e: Exception) {
            Outcome("I couldn't get the weather right now. Please check your internet connection.")
        }
    }

    private suspend fun currency(p: Plan): Outcome = withContext(Dispatchers.IO) {
        try {
            val amount = p.value.toDoubleOrNull() ?: 1.0
            val res = JSONObject(Net.get("https://api.frankfurter.dev/v1/latest?amount=$amount&base=${p.target}&symbols=${p.message}"))
            val v = res.getJSONObject("rates").getDouble(p.message)
            info("${Calc.format(amount, 2)} ${p.target} is about ${Calc.format(v, 2)} ${p.message}.")
        } catch (e: Exception) {
            Outcome("I couldn't get exchange rates right now.")
        }
    }
}
