package com.jarvis.assistant.core

/** Makes text safe for the voice: codes are spelled out, markdown symbols are dropped. */
object SpeechText {
    private val plainUnit = Regex("(?i)^\\d+(st|nd|rd|th|am|pm|km|kg|gb|mb|tb|kb|g|k|m|cm|mm|ms|hz|mhz|ghz|x|s|h|d|l|ml|w|v|a|p)$")
    private val code = Regex("\\b(?=[A-Za-z0-9]*\\d)(?=[A-Za-z0-9]*[A-Za-z])[A-Za-z0-9]{3,}\\b")
    private val longDigits = Regex("(?<![\\d.,])\\d{6,}(?![\\d.,])")

    private fun spell(s: String) = s.toCharArray().joinToString(" ") { c ->
        if (c.isLetter()) c.uppercaseChar().toString() else c.toString()
    }

    fun clean(text: String): String {
        var t = text
        t = t.replace(Regex("[*#`_>|~]+"), " ")
        t = t.replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
        t = t.replace(Regex("https?://\\S+"), " link ")
        t = code.replace(t) { if (plainUnit.matches(it.value)) it.value else spell(it.value) }
        t = longDigits.replace(t) { spell(it.value) }
        return t.replace(Regex("\\s+"), " ").trim()
    }

    /** Keeps what is spoken within what the voice engine can take, cutting at the end of a sentence. */
    fun limit(t: String, max: Int): String {
        if (t.length <= max) return t
        val cut = t.substring(0, max)
        val end = maxOf(cut.lastIndexOf(". "), cut.lastIndexOf("! "), cut.lastIndexOf("? "))
        return if (end > max / 2) cut.substring(0, end + 1) else cut
    }
}
