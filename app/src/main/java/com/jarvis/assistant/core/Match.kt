package com.jarvis.assistant.core

/** Name / app matching. Pure Kotlin (no Android) so it can be unit tested. */
object Match {

    fun norm(s: String): String = s.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), "").replace(Regex("\\s+"), " ").trim()

    private val groups = listOf(
        setOf("mom", "mum", "mummy", "mommy", "mother", "amma", "mama", "maa"),
        setOf("dad", "daddy", "papa", "father", "appa", "baba", "pa"),
        setOf("bro", "brother", "bhai"),
        setOf("sis", "sister", "didi")
    )

    /** "mum" should find a contact saved as "Mom", and so on. */
    fun expand(q: String): List<String> {
        val out = mutableListOf(q)
        for (g in groups) if (q in g) out.addAll(g.filter { it != q })
        return out
    }

    fun score(name: String, query: String): Int = expand(query).maxOf { scoreOne(name, it) }

    /** Exact > prefix > whole word > substring > fuzzy (mis-heard names). */
    private fun scoreOne(name: String, q: String): Int {
        if (q.isEmpty() || name.isEmpty()) return 0
        if (name == q) return 100
        if (name.startsWith("$q ")) return 85
        if (name.startsWith(q)) return 80
        val words = name.split(" ")
        if (words.any { it == q }) return 75
        if (name.contains(q)) return 60
        val qWords = q.split(" ")
        if (qWords.size > 1 && qWords.all { w -> words.any { it == w || it.startsWith(w) } }) return 70
        if (q.length >= 4) {
            val tol = if (q.length >= 7) 2 else 1
            if (words.any { lev(it, q) <= tol }) return 40
            if (lev(name, q) <= tol) return 40
        }
        return 0
    }

    fun lev(a: String, b: String): Int {
        if (a == b) return 0
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }
}
