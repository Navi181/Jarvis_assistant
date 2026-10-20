package com.jarvis.assistant.core

/** Everything said to and by Jarvis, kept on the phone so it can be read, copied and shared later. */
object History {
    data class Entry(val fromUser: Boolean, val text: String, val time: Long)

    private const val MAX = 300

    fun all(): List<Entry> = try {
        Prefs.rawHistory().split('\u001F').filter { it.isNotBlank() }.mapNotNull {
            val p = it.split('\u001E')
            if (p.size < 3) null else Entry(p[0] == "u", p[2], p[1].toLongOrNull() ?: 0L)
        }
    } catch (e: Exception) { emptyList() }

    fun add(fromUser: Boolean, text: String) {
        if (text.isBlank()) return
        try {
            val clean = text.replace('\u001F', ' ').replace('\u001E', ' ').take(4000)
            val line = (if (fromUser) "u" else "j") + "\u001E" + System.currentTimeMillis() + "\u001E" + clean
            val keep = Prefs.rawHistory().split('\u001F').filter { it.isNotBlank() }.takeLast(MAX - 1)
            Prefs.saveRawHistory((keep + line).joinToString("\u001F"))
        } catch (e: Exception) { }
    }

    fun clear() = try { Prefs.saveRawHistory("") } catch (e: Exception) { }

    fun asText(list: List<Entry> = all()): String = list.joinToString("\n\n") { (if (it.fromUser) "You: " else "Jarvis: ") + it.text }
}
