package com.jarvis.assistant.core

import android.content.Context
import com.jarvis.assistant.system.JarvisAccessibilityService
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * "Do anything on my phone": Jarvis looks at the screen (through Accessibility), asks the AI what to tap or type next,
 * does it, and repeats until the job is done. Anything that spends money, deletes things or needs a password is left
 * for you to confirm yourself.
 */
object PhoneAgent {
    private const val MAX_STEPS = 14

    private val risky = Regex(
        "(?i)\\b(pay|pay now|place order|buy now|purchase|confirm order|checkout|send money|transfer|delete|remove account|factory reset|erase|uninstall|sign out|log out|password|pin|otp)\\b"
    )

    private fun system() = """
You control an Android phone for the user by looking at the screen and choosing ONE action at a time.
Reply with ONLY a JSON object: {"do": "...", "id": number, "text": "...", "reply": "..."}.
"do" is one of:
- tap (id = number of the item to tap)
- type (id = number of a field, text = what to type; add "submit": true to press enter/search)
- scroll_down | scroll_up
- back | home | recents | notifications | quick_settings
- open_app (text = app name)
- wait (screen is still loading)
- done (the goal is finished; reply = one short spoken sentence telling the user what happened or the answer)
- ask (you need something from the user or they must do the final step themselves; reply = the short question or instruction)
Rules: use open_app to start. Never invent ids: use only numbers shown. Never complete payments, orders, deletions, or enter passwords or one-time codes: stop with "ask" so the user does that last tap. Keep replies short and spoken-friendly, no markdown.
""".trimIndent()

    suspend fun run(ctx: Context, goal: String): String {
        val svc0 = JarvisAccessibilityService.instance
        if (svc0 == null) {
            return "To do that I need Accessibility access. Open setup, go to Accessibility and switch Jarvis on."
        }
        if (!Prefs.hasAnyKey) return "I need an AI key to work the phone. Add one in setup."
        val log = ArrayList<String>()
        for (step in 1..MAX_STEPS) {
            val svc = JarvisAccessibilityService.instance ?: return "Accessibility was switched off, so I stopped."
            val (pkg, items) = svc.items()
            val screen = if (items.isEmpty()) "(nothing readable on screen)" else items.joinToString("\n") { it.line }
            val prompt = "Goal: $goal\nStep $step of $MAX_STEPS.\nWhat I did so far: " +
                (if (log.isEmpty()) "nothing yet" else log.takeLast(6).joinToString("; ")) +
                "\nApp in front: $pkg\nScreen:\n$screen"
            val raw = try {
                Llm.chat(system(), emptyList(), prompt, json = true, temperature = 0.2)
            } catch (e: LlmFailure) {
                return Brain.friendly(e)
            } catch (e: Exception) {
                return "I lost my train of thought. Please try again."
            }
            val o = try {
                JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1))
            } catch (e: Exception) {
                log.add("(bad reply)")
                continue
            }
            val act = o.optString("do").lowercase()
            val id = o.optInt("id", -1)
            val text = o.optString("text")
            val reply = o.optString("reply")
            val item = items.getOrNull(id)
            when (act) {
                "done" -> return reply.ifBlank { "Done." }
                "ask" -> return reply.ifBlank { "I need you to take it from here." }
                "tap" -> {
                    if (item == null) { log.add("tap failed: no item $id"); continue }
                    if (risky.containsMatchIn(item.label)) return "I've got it to \"${item.label}\". I'll leave that last tap to you."
                    log.add("tapped \"${item.label}\"" + if (svc.click(item)) "" else " (didn't work)")
                }
                "type" -> {
                    if (item == null) { log.add("type failed: no item $id"); continue }
                    if (risky.containsMatchIn(item.label) || risky.containsMatchIn(text)) return "That needs a password or code, so I'll leave it to you."
                    log.add("typed \"$text\"" + if (svc.type(item, text, o.optBoolean("submit", false))) "" else " (didn't work)")
                }
                "scroll_down" -> log.add("scrolled down" + if (svc.scroll(items, true)) "" else " (can't)")
                "scroll_up" -> log.add("scrolled up" + if (svc.scroll(items, false)) "" else " (can't)")
                "back", "home", "recents", "notifications", "quick_settings" -> log.add("pressed $act" + if (svc.global(act)) "" else " (can't)")
                "open_app" -> {
                    val r = Actions.run(ctx, Plan("open_app", target = text))
                    log.add("opened $text: ${r.reply}")
                }
                "wait" -> log.add("waited")
                else -> log.add("(unknown action)")
            }
            delay(if (act == "open_app") 2500L else 1300L)
        }
        return "I got partway but ran out of steps. Tell me what's on screen and I'll carry on."
    }
}
