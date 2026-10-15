package com.jarvis.assistant.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * A vague spoken photo request turned into filters: "open a photo of sunset in goa last week".
 * Pure Kotlin (no Android classes) so it is covered by the unit tests on every build.
 * People are only ever COUNTED, never identified.
 */
data class PhotoQuery(
    val scenes: List<String>,
    val place: String,
    val fromMs: Long,
    val toMs: Long,
    val minPeople: Int,
    val screenshots: Boolean
) {
    val hasDate: Boolean get() = fromMs > 0L || toMs < Long.MAX_VALUE
    val hasFilters: Boolean get() = scenes.isNotEmpty() || place.isNotBlank() || minPeople > 0

    companion object {
        private val noun = Regex("\\b(photos?|pictures?|pics?|images?|screenshots?|selfies?)\\b")
        private val verb = Regex("\\b(open|show|find|get|see|display|pull up|bring up|look at|view|search)\\b")

        private val months = listOf(
            "january", "february", "march", "april", "may", "june",
            "july", "august", "september", "october", "november", "december"
        )

        private val scenes = setOf(
            "beach", "sea", "ocean", "mountain", "hill", "sunset", "sunrise", "sky", "food", "pizza", "cake",
            "cat", "dog", "flower", "snow", "forest", "tree", "garden", "lake", "river", "waterfall", "city",
            "building", "temple", "car", "bike", "bird", "night", "party", "wedding", "birthday", "baby",
            "desert", "road", "bridge", "park", "school", "book", "laptop", "concert", "stadium"
        )

        private val dateWords = Regex(
            "\\b(?:yesterday|today|last week|this week|last month|this month|last year|this year|" +
                "(?:in |from |during )?(?:${months.joinToString("|")})(?: \\d{4})?)\\b"
        )

        private val peoplePhrases = Regex(
            "\\bwith (?:\\d+|a few|some|many) (?:people|persons|friends|guys|girls|boys|of us)\\b|" +
                "\\b\\d+ (?:people|persons)\\b|\\bgroup (?:photo|pic|picture|selfie)s?\\b|" +
                "\\bwith (?:my |our )?(?:friends|family|people|everyone)\\b|\\bme and my (?:friends|family)\\b"
        )

        private fun singular(w: String): String = when {
            w in scenes -> w
            w.endsWith("es") && w.dropLast(2) in scenes -> w.dropLast(2)
            w.endsWith("s") && w.dropLast(1) in scenes -> w.dropLast(1)
            else -> w
        }

        private val skipStart = Regex("^(?:my|me|us|our|him|her|them|his|their|you|it)\\b")

        fun parse(raw: String, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): PhotoQuery? {
            val t = Num.digitize(raw.lowercase().trim().trimEnd('.', '!', '?', ','))
            if (!noun.containsMatchIn(t) || !verb.containsMatchIn(t)) return null

            // ---- date
            var from = 0L
            var to = Long.MAX_VALUE
            fun ms(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()
            fun range(a: LocalDate, b: LocalDate) { from = ms(a); to = ms(b) }
            val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val monthHit = Regex("\\b(${months.joinToString("|")})(?: (\\d{4}))?\\b").find(t)
            when {
                "yesterday" in t -> range(today.minusDays(1), today)
                "today" in t -> range(today, today.plusDays(1))
                "last week" in t -> range(monday.minusWeeks(1), monday)
                "this week" in t -> range(monday, today.plusDays(1))
                "last month" in t -> range(today.withDayOfMonth(1).minusMonths(1), today.withDayOfMonth(1))
                "this month" in t -> range(today.withDayOfMonth(1), today.plusDays(1))
                "last year" in t -> range(LocalDate.of(today.year - 1, 1, 1), LocalDate.of(today.year, 1, 1))
                "this year" in t -> range(LocalDate.of(today.year, 1, 1), today.plusDays(1))
                monthHit != null -> {
                    val m = months.indexOf(monthHit.groupValues[1]) + 1
                    val y = monthHit.groupValues[2].toIntOrNull() ?: if (m > today.monthValue) today.year - 1 else today.year
                    val start = LocalDate.of(y, m, 1)
                    range(start, start.plusMonths(1))
                }
            }

            // ---- people (counted, never identified)
            var minPeople = 0
            Regex("\\b(\\d+)\\s+(?:people|persons|friends|guys|girls|boys|of us)\\b").find(t)?.let {
                minPeople = it.groupValues[1].toIntOrNull() ?: 0
            }
            if (minPeople == 0 && peoplePhrases.containsMatchIn(t)) minPeople = 2
            if (minPeople == 0 && Regex("\\bselfies?\\b").containsMatchIn(t)) minPeople = 1

            // ---- scenes
            val found = ArrayList<String>()
            Regex("[a-z]+").findAll(t).forEach { m ->
                val w = singular(m.value)
                if (w in scenes && w !in found) found.add(w)
            }

            // ---- place: whatever follows in / at / near / from / of once dates, people and scenes are removed
            var rest = t.replace(dateWords, " ").replace(peoplePhrases, " ")
            rest = rest.replace(Regex("\\b(?:taken|photos?|pictures?|pics?|images?|screenshots?)\\b"), " ")
                .replace(Regex("\\s+"), " ").trim()
            var place = ""
            val m = Regex("\\b(?:in|at|near|around|from)\\s+(.+)$").find(rest) ?: Regex("\\bof\\s+(.+)$").find(rest)
            if (m != null) {
                var cand = m.groupValues[1].replace(Regex("^(?:the|a|an)\\s+"), "").trim()
                cand = cand.replace(Regex("\\s+(?:please|now|for me)$"), "").trim()
                val words = cand.split(" ")
                val allScene = words.isNotEmpty() && words.all { singular(it) in scenes || it in setOf("and", "the", "of") }
                if (cand.isNotEmpty() && !allScene && !skipStart.containsMatchIn(cand) && cand !in setOf("last", "recent", "latest")) place = cand
            }
            val screenshots = Regex("\\bscreenshots?\\b").containsMatchIn(t)
            return PhotoQuery(found, place, from, to, minPeople, screenshots)
        }
    }
}
