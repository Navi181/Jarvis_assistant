package com.jarvis.assistant.core

/**
 * Routines: one phrase runs several commands in a row ("good night" = read tomorrow's schedule, silence the phone, dim the screen).
 * A few come built in; you can make your own by voice, and they are kept on the phone.
 * Each step is an ordinary command, so anything Jarvis understands can be a step.
 */
object Routines {
    data class Routine(val name: String, val steps: List<String>)

    val builtins = listOf(
        Routine("good night", listOf("what's on my schedule tomorrow", "silent", "set brightness to 10 percent")),
        Routine("study mode", listOf("silent", "set a timer for 25 minutes")),
        Routine("movie mode", listOf("silent", "set brightness to 30 percent"))
    )

    private val alias = mapOf("goodnight" to "good night", "bedtime" to "good night", "night mode" to "good night", "cinema mode" to "movie mode")

    private fun norm(s: String): String = s.lowercase()
        .replace(Regex("^(?:my|the|a)\\s+"), "").replace(Regex("\\s+routine$"), "").replace(Regex("[^a-z0-9 ]"), "").trim()
        .let { alias[it] ?: it }

    private fun custom(): List<Routine> = try { Prefs.routines() } catch (e: Exception) { emptyList() }

    fun all(): List<Routine> = custom() + builtins.filter { b -> custom().none { norm(it.name) == norm(b.name) } }

    fun find(name: String): Routine? {
        val n = norm(name)
        if (n.isEmpty()) return null
        return all().firstOrNull { norm(it.name) == n }
    }

    private val verbs = "turn|set|open|play|call|text|message|silence|silent|mute|unmute|put|start|read|tell|dim|brighten|enable|disable|switch|launch|remind|what|show|send|navigate|lock"

    fun splitSteps(text: String): List<String> =
        text.split(Regex("\\s*[,;]\\s*|\\s+(?:and then|then|and)\\s+(?=(?:$verbs)\\b)", RegexOption.IGNORE_CASE))
            .map { it.trim().trimEnd('.', '!') }.filter { it.isNotBlank() }.take(10)

    /** Understands saying, listing, deleting and running routines. Null when [t] is something else. */
    fun parse(t: String): Plan? {
        if (Regex("^(?:list|show|what are) (?:my |all )?routines$").matches(t)) return Plan("routine_list")
        Regex("^(?:delete|remove|forget) (?:my |the )?(.+?) routine$").find(t)?.let { return Plan("routine_delete", target = it.groupValues[1]) }
        Regex("^(?:create|make|add|save|set up|new)(?: an?| new)* routine (?:called |named |for )?(.+?)\\s*(?::|,|\\bthat\\b|\\bwhich\\b|\\bto\\b|\\bwhere\\b|\\bwith\\b)\\s+(.+)$").find(t)?.let {
            return Plan("routine_save", target = it.groupValues[1].trim(), message = it.groupValues[2].trim())
        }
        Regex("^(?:run|start|do|activate|begin|launch|trigger|enable)\\s+(.+)$").find(t)?.let { m ->
            if (find(m.groupValues[1]) != null) return Plan("routine_run", target = m.groupValues[1])
        }
        if (find(t) != null) return Plan("routine_run", target = t)
        return null
    }
}
