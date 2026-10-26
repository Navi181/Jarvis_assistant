package com.jarvis.assistant.core

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

/**
 * Everyday life in one place: money spent, habits, lists, calendar changes, and a library of saved files.
 * Everything is kept on the phone.
 */
object Life {
    val actions = setOf(
        "expense_add", "expense_report", "habit_log", "habit_show", "habit_add",
        "list_add", "list_show", "list_remove", "list_clear", "list_share",
        "cal_move", "cal_cancel", "cal_free",
        "lib_save", "lib_ask", "lib_list", "fix_add"
    )

    private const val SEP = "\u001F"
    private const val SUB = "\u001E"

    private fun ask(q: String, p: Plan) = Outcome(q, awaiting = p, keepOpen = true)

    suspend fun run(ctx: Context, p: Plan): Outcome = when (p.action) {
        "expense_add" -> expenseAdd(p)
        "expense_report" -> expenseReport(p)
        "habit_add" -> { habit(p.target); Outcome("Okay, I'll track ${p.target}. Say log ${p.target} each day you do it.") }
        "habit_log" -> habitLog(p)
        "habit_show" -> habitShow(p)
        "list_add" -> listAdd(p)
        "list_show" -> listShow(p)
        "list_remove" -> listRemove(p)
        "list_clear" -> { lists(p.target.ifBlank { "shopping" }, emptyList()); Outcome("Cleared your ${p.target.ifBlank { "shopping" }} list.") }
        "list_share" -> listShare(ctx, p)
        "cal_move" -> calMove(ctx, p)
        "cal_cancel" -> calCancel(ctx, p)
        "cal_free" -> calFree(ctx, p)
        "lib_save" -> libSave(ctx, p)
        "lib_ask" -> libAsk(p)
        "lib_list" -> libList()
        "fix_add" -> { addFix(p.target, p.value); Outcome("Okay. When you say ${p.target}, I'll hear ${p.value}.") }
        else -> Outcome("")
    }

    // ------------------------------------------------------------ what you said, corrected

    private fun fixes(): List<Pair<String, String>> = try {
        Prefs.blob("fixes").split(SEP).filter { it.contains(SUB) }.map { it.substringBefore(SUB) to it.substringAfter(SUB) }
    } catch (e: Exception) { emptyList() }

    private fun addFix(from: String, to: String) {
        val keep = fixes().filter { it.first != from.lowercase() } + (from.lowercase().trim() to to.trim())
        Prefs.setBlob("fixes", keep.takeLast(100).joinToString(SEP) { it.first + SUB + it.second })
    }

    /** Applies "when I say X I mean Y" to what was heard. */
    fun fix(heard: String): String {
        var t = heard
        for ((a, b) in fixes()) t = t.replace(Regex("\\b" + Regex.escape(a) + "\\b", RegexOption.IGNORE_CASE), b)
        return t
    }

    // ------------------------------------------------------------ money

    private fun expenseAdd(p: Plan): Outcome {
        val amt = p.value.toDoubleOrNull() ?: return Outcome("How much was it?")
        val note = p.message.trim().ifBlank { "something" }
        Prefs.setBlob("expenses", (Prefs.blob("expenses").split(SEP).filter { it.isNotBlank() } + "${System.currentTimeMillis()}$SUB$amt$SUB$note").takeLast(3000).joinToString(SEP))
        return Outcome("Noted. ${money(amt)} on $note.")
    }

    private fun money(a: Double) = "₹" + (if (a == Math.floor(a)) a.toLong().toString() else String.format(Locale.US, "%.2f", a))

    private fun expenseReport(p: Plan): Outcome {
        val period = p.value.ifBlank { "today" }
        val start = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0) }
        var end = Long.MAX_VALUE
        when (period) {
            "yesterday" -> { end = start.timeInMillis; start.add(java.util.Calendar.DAY_OF_YEAR, -1) }
            "this week", "week" -> start.set(java.util.Calendar.DAY_OF_WEEK, start.firstDayOfWeek)
            "this month", "month" -> start.set(java.util.Calendar.DAY_OF_MONTH, 1)
        }
        val rows = Prefs.blob("expenses").split(SEP).mapNotNull {
            val x = it.split(SUB)
            if (x.size < 3) null else Triple(x[0].toLongOrNull() ?: 0L, x[1].toDoubleOrNull() ?: 0.0, x[2])
        }.filter { it.first >= start.timeInMillis && it.first < end }
        if (rows.isEmpty()) return Outcome("You haven't logged any spending $period.")
        val total = rows.sumOf { it.second }
        val top = rows.groupBy { it.third }.mapValues { e -> e.value.sumOf { it.second } }.entries.sortedByDescending { it.value }.take(3)
        return Outcome("You spent ${money(total)} $period. Most of it on " + top.joinToString(", ") { "${it.key}, ${money(it.value)}" } + ".", keepOpen = true)
    }

    // ------------------------------------------------------------ habits

    private fun habits(): MutableMap<String, MutableSet<String>> {
        val m = LinkedHashMap<String, MutableSet<String>>()
        Prefs.blob("habits").split(SEP).filter { it.contains(SUB) }.forEach { m[it.substringBefore(SUB)] = it.substringAfter(SUB).split(',').filter { d -> d.isNotBlank() }.toMutableSet() }
        return m
    }

    private fun saveHabits(m: Map<String, Set<String>>) = Prefs.setBlob("habits", m.entries.joinToString(SEP) { it.key + SUB + it.value.takeLast(400).joinToString(",") })

    private fun habit(name: String) { val m = habits(); m.getOrPut(name.lowercase().trim()) { mutableSetOf() }; saveHabits(m) }

    private fun streak(days: Set<String>): Int {
        var d = LocalDate.now()
        if (d.toString() !in days) d = d.minusDays(1)
        var n = 0
        while (d.toString() in days) { n++; d = d.minusDays(1) }
        return n
    }

    private fun habitLog(p: Plan): Outcome {
        val name = p.target.lowercase().trim()
        val m = habits()
        val key = m.keys.firstOrNull { it == name || it.contains(name) || name.contains(it) } ?: name
        m.getOrPut(key) { mutableSetOf() }.add(LocalDate.now().toString())
        saveHabits(m)
        val s = streak(m[key]!!)
        return Outcome("Logged $key. " + if (s > 1) "That's $s days in a row." else "A fresh start.")
    }

    private fun habitShow(p: Plan): Outcome {
        val m = habits()
        if (m.isEmpty()) return Outcome("You aren't tracking any habits yet. Say track a habit, then its name.")
        if (p.target.isNotBlank()) {
            val k = m.keys.firstOrNull { it.contains(p.target.lowercase().trim()) } ?: return Outcome("I'm not tracking ${p.target}.")
            return Outcome("Your $k streak is ${streak(m[k]!!)} days, ${m[k]!!.size} days in total.")
        }
        return Outcome(m.entries.joinToString(". ") { "${it.key}, ${streak(it.value)} day streak" } + ".")
    }

    // ------------------------------------------------------------ lists

    private fun allLists(): MutableMap<String, MutableList<String>> {
        val m = LinkedHashMap<String, MutableList<String>>()
        Prefs.blob("lists").split("\n").filter { it.contains(SUB) }.forEach {
            val x = it.split(SUB); m[x[0]] = x.drop(1).filter { s -> s.isNotBlank() }.toMutableList()
        }
        return m
    }

    private fun lists(name: String, items: List<String>) {
        val m = allLists(); m[name.lowercase().trim()] = items.toMutableList()
        Prefs.setBlob("lists", m.entries.joinToString("\n") { it.key + SUB + it.value.joinToString(SUB) })
    }

    private fun splitItems(s: String) = s.split(Regex("\\s*(?:,|\\band\\b|&)\\s*")).map { it.trim() }.filter { it.isNotBlank() }

    private fun listAdd(p: Plan): Outcome {
        val name = p.target.ifBlank { "shopping" }.lowercase()
        val items = splitItems(p.message)
        if (items.isEmpty()) return ask("What should I add?", p)
        val cur = allLists()[name].orEmpty()
        lists(name, (cur + items.filter { i -> cur.none { it.equals(i, true) } }))
        return Outcome("Added ${items.joinToString(", ")} to your $name list.")
    }

    private fun listShow(p: Plan): Outcome {
        val name = p.target.ifBlank { "shopping" }.lowercase()
        val l = allLists()[name].orEmpty()
        return if (l.isEmpty()) Outcome("Your $name list is empty.") else Outcome("Your $name list has ${l.size} item${if (l.size > 1) "s" else ""}: " + l.joinToString(", ") + ".", keepOpen = true)
    }

    private fun listRemove(p: Plan): Outcome {
        val name = p.target.ifBlank { "shopping" }.lowercase()
        val cur = allLists()[name].orEmpty()
        val gone = splitItems(p.message)
        val left = cur.filter { c -> gone.none { c.contains(it, true) } }
        lists(name, left)
        return Outcome(if (left.size == cur.size) "I couldn't find that on your $name list." else "Removed ${gone.joinToString(", ")} from your $name list.")
    }

    private fun listShare(ctx: Context, p: Plan): Outcome {
        val name = p.target.ifBlank { "shopping" }.lowercase()
        val l = allLists()[name].orEmpty()
        if (l.isEmpty()) return Outcome("Your $name list is empty.")
        return try {
            ctx.startActivity(
                Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, name.replaceFirstChar { it.uppercase() } + " list:\n" + l.joinToString("\n") { "• $it" }), "Share list")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            Outcome("Choose where to send it.")
        } catch (e: Exception) { Outcome("I couldn't open sharing.") }
    }

    // ------------------------------------------------------------ calendar changes

    private fun calOk(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private class Ev(val eventId: Long, val title: String, val begin: Long, val end: Long, val allDay: Boolean)

    private fun events(ctx: Context, from: Long, to: Long): List<Ev> {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it, from); ContentUris.appendId(it, to) }.build()
        val out = ArrayList<Ev>()
        try {
            ctx.contentResolver.query(
                uri, arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY),
                null, null, CalendarContract.Instances.BEGIN + " ASC"
            )?.use { q -> while (q.moveToNext()) out.add(Ev(q.getLong(0), q.getString(1).orEmpty(), q.getLong(2), q.getLong(3), q.getInt(4) == 1)) }
        } catch (e: Exception) { }
        return out
    }

    private fun find(ctx: Context, title: String): Ev? {
        val now = System.currentTimeMillis()
        val t = title.lowercase().trim()
        return events(ctx, now - 3_600_000L, now + 14 * 86_400_000L).firstOrNull { it.title.lowercase().contains(t) && !it.allDay }
    }

    private val fmt get() = SimpleDateFormat("EEE h:mm a", Locale.getDefault())

    private fun calMove(ctx: Context, p: Plan): Outcome {
        if (!calOk(ctx)) return Outcome("I need calendar access first. Allow it in setup, then ask me again.")
        val ev = find(ctx, p.target) ?: return Outcome("I couldn't find ${p.target} in the next two weeks.")
        if (p.hour !in 0..23) return Outcome("What time should I move it to?")
        val c = java.util.Calendar.getInstance().apply { timeInMillis = ev.begin; set(java.util.Calendar.HOUR_OF_DAY, p.hour); set(java.util.Calendar.MINUTE, p.minute); set(java.util.Calendar.SECOND, 0) }
        val len = (ev.end - ev.begin).coerceAtLeast(30 * 60_000L)
        val v = ContentValues().apply { put(CalendarContract.Events.DTSTART, c.timeInMillis); put(CalendarContract.Events.DTEND, c.timeInMillis + len) }
        return try {
            ctx.contentResolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, ev.eventId), v, null, null)
            Outcome("Moved ${ev.title} to ${fmt.format(Date(c.timeInMillis))}.")
        } catch (e: Exception) { Outcome("I couldn't change that event. It may belong to a calendar I can't edit.") }
    }

    private fun calCancel(ctx: Context, p: Plan): Outcome {
        if (!calOk(ctx)) return Outcome("I need calendar access first. Allow it in setup, then ask me again.")
        val ev = find(ctx, p.target) ?: return Outcome("I couldn't find ${p.target} in the next two weeks.")
        if (p.value != "yes") return ask("Cancel ${ev.title} on ${fmt.format(Date(ev.begin))}?", p)
        return try {
            ctx.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, ev.eventId), null, null)
            Outcome("Cancelled ${ev.title}.")
        } catch (e: Exception) { Outcome("I couldn't delete that event.") }
    }

    private fun calFree(ctx: Context, p: Plan): Outcome {
        if (!calOk(ctx)) return Outcome("I need calendar access first. Allow it in setup, then ask me again.")
        val day = java.util.Calendar.getInstance()
        if (p.value == "tomorrow") day.add(java.util.Calendar.DAY_OF_YEAR, 1)
        fun at(h: Int) = (day.clone() as java.util.Calendar).apply { set(java.util.Calendar.HOUR_OF_DAY, h); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0) }.timeInMillis
        var cursor = maxOf(at(8), if (p.value == "tomorrow") 0L else System.currentTimeMillis())
        val endDay = at(21)
        val busy = events(ctx, at(0), at(23)).filter { !it.allDay }
        val gaps = ArrayList<String>()
        val t = SimpleDateFormat("h:mm a", Locale.getDefault())
        for (e in busy) {
            if (e.begin - cursor >= 45 * 60_000L) gaps.add("${t.format(Date(cursor))} to ${t.format(Date(e.begin))}")
            cursor = maxOf(cursor, e.end)
        }
        if (endDay - cursor >= 45 * 60_000L) gaps.add("${t.format(Date(cursor))} to ${t.format(Date(endDay))}")
        val word = if (p.value == "tomorrow") "tomorrow" else "today"
        return Outcome(if (gaps.isEmpty()) "You're booked solid $word." else "You're free $word " + gaps.take(4).joinToString(", and ") + ".", keepOpen = true)
    }

    // ------------------------------------------------------------ library of saved files

    private fun libDir(ctx: Context) = File(ctx.filesDir, "library").also { it.mkdirs() }
    private fun libIndex(ctx: Context) = File(libDir(ctx), "index.txt")

    private class Item(val name: String, val time: Long, val summary: String)

    private fun items(ctx: Context): List<Item> = try {
        libIndex(ctx).takeIf { it.exists() }?.readText()?.split(SEP)?.filter { it.contains(SUB) }?.map {
            val x = it.split(SUB); Item(x[0], x.getOrNull(1)?.toLongOrNull() ?: 0L, x.getOrElse(2) { "" })
        } ?: emptyList()
    } catch (e: Exception) { emptyList() }

    private fun libSave(ctx: Context, p: Plan): Outcome {
        val a = Assistant.media.value ?: return Outcome("Attach a photo or file first with the paperclip, then say save this.")
        val name = p.message.ifBlank { a.name }.replace(SUB, " ").replace(SEP, " ")
        val summary = try {
            MediaAsk.analyze(ctx, a, "Summarise this for a personal library so it can be looked up later: what it is, key people, dates, amounts, numbers, reference codes and anything else worth finding again. Plain text, at most 150 words.", false)
        } catch (e: Exception) { return Outcome(MediaAsk.friendly(e)) }
        try {
            ctx.contentResolver.openInputStream(a.uri)?.use { i -> File(libDir(ctx), "${System.currentTimeMillis()}_" + a.name.replace(Regex("[^A-Za-z0-9._-]"), "_")).outputStream().use { o -> i.copyTo(o) } }
        } catch (e: Exception) { }
        val cur = libIndex(ctx).takeIf { it.exists() }?.readText().orEmpty().split(SEP).filter { it.isNotBlank() }
        libIndex(ctx).writeText((cur + (name + SUB + System.currentTimeMillis() + SUB + summary.replace(SEP, " ").replace(SUB, " "))).takeLast(300).joinToString(SEP))
        return Outcome("Saved $name to your library. Ask me about it any time, for example: what was the amount on my bill.")
    }

    private fun libList(): Outcome = Outcome("Open Jarvis setup to see your library.")

    private suspend fun libAsk(p: Plan): Outcome {
        val ctx = Assistant.appContext()
        val all = items(ctx)
        if (all.isEmpty()) return Outcome("Your library is empty. Attach a file and say save this to start it.")
        val corpus = all.takeLast(40).joinToString("\n\n") { "ITEM: ${it.name} (${SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(it.time))})\n${it.summary}" }.take(14000)
        return Outcome(Brain.fromLibrary(p.message, corpus), keepOpen = true)
    }

    /** What the Setup screen shows. */
    fun libraryNames(ctx: Context): List<String> = items(ctx).map { it.name }

    fun libraryClear(ctx: Context) { libDir(ctx).listFiles()?.forEach { it.delete() } }

    /** Wipes everything Jarvis keeps about you on this phone (not your AI keys). */
    fun eraseAll(ctx: Context) { Prefs.eraseData(); libraryClear(ctx) }
}
