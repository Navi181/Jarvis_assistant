package com.jarvis.assistant.core

/** Turns spoken number words into digits: "twenty five" -> "25". Only used where a number is expected. */
object Num {
    private val units = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
        "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
        "eighteen" to 18, "nineteen" to 19
    )
    private val tens = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
        "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90
    )

    fun digitize(s: String): String {
        val tokens = s.split(" ").filter { it.isNotEmpty() }
        val out = ArrayList<String>(tokens.size)
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            val tv = tens[t]
            if (tv != null) {
                val next = tokens.getOrNull(i + 1)?.let { units[it] }
                if (next != null && next in 1..9) {
                    out.add((tv + next).toString())
                    i += 2
                } else {
                    out.add(tv.toString())
                    i++
                }
                continue
            }
            out.add(units[t]?.toString() ?: t)
            i++
        }
        return out.joinToString(" ")
    }
}
