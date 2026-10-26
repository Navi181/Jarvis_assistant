package com.jarvis.assistant.system

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/** Reads and writes the text field you are in, in any app, through the accessibility service. */
object VoiceLayer {
    class Field(val node: AccessibilityNodeInfo, val pkg: String, val text: String, val start: Int, val end: Int) {
        val hasSelection get() = start >= 0 && end > start
    }

    fun pkg(): String = try {
        JarvisAccessibilityService.instance?.rootInActiveWindow?.packageName?.toString().orEmpty()
    } catch (e: Exception) { "" }

    /** The text box with the cursor, if there is one. */
    fun field(): Field? = try {
        val svc = JarvisAccessibilityService.instance
        val root = svc?.rootInActiveWindow
        val n = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (n == null || !n.isEditable) null else {
            val txt = if (n.isShowingHintText) "" else n.text?.toString().orEmpty()
            Field(n, root?.packageName?.toString().orEmpty(), txt, n.textSelectionStart, n.textSelectionEnd)
        }
    } catch (e: Exception) { null }

    private fun setText(n: AccessibilityNodeInfo, text: String, cursor: Int): Boolean {
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        val ok = n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok) {
            val sel = Bundle()
            sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor.coerceIn(0, text.length))
            sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor.coerceIn(0, text.length))
            n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
        }
        return ok
    }

    /** Types [text] at the cursor (replacing any selection). Falls back to paste. */
    fun insert(ctx: Context, text: String): Boolean {
        val f = field() ?: return false
        val s = if (f.start >= 0) f.start.coerceAtMost(f.text.length) else f.text.length
        val e = if (f.end >= 0) f.end.coerceAtMost(f.text.length).coerceAtLeast(s) else s
        val needSpace = s > 0 && !f.text[s - 1].isWhitespace() && !text.first().isWhitespace() && text.first() !in ".,!?;:"
        val ins = (if (needSpace) " " else "") + text
        val ok = setText(f.node, f.text.substring(0, s) + ins + f.text.substring(e), s + ins.length)
        if (ok) return true
        return try {
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Jarvis", ins))
            f.node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (e2: Exception) { false }
    }

    /** Replaces the selected text, or the whole box when nothing is selected. */
    fun replace(ctx: Context, f: Field, text: String): Boolean {
        val s = if (f.hasSelection) f.start else 0
        val e = if (f.hasSelection) f.end else f.text.length
        val out = f.text.substring(0, s.coerceAtMost(f.text.length)) + text + f.text.substring(e.coerceAtMost(f.text.length))
        if (setText(f.node, out, s + text.length)) return true
        return try {
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Jarvis", text))
            if (!f.hasSelection) {
                val a = Bundle()
                a.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                a.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, f.text.length)
                f.node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, a)
            }
            f.node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (e2: Exception) { false }
    }

    /** The writing style that suits the app you are in. */
    fun tone(pkg: String): String = when {
        Regex("whatsapp|telegram|messaging|mms|sms|instagram|snapchat|signal|discord|facebook\\.orca|messenger").containsMatchIn(pkg) -> "casual and friendly, like a chat message"
        Regex("gm\\.gm|gmail|outlook|mail|linkedin|teams|slack|office|docs\\.editors").containsMatchIn(pkg) -> "polite and professional"
        Regex("keep|notes|notion|obsidian|evernote").containsMatchIn(pkg) -> "clear and neutral, like a personal note"
        Regex("twitter|x\\.android|threads|reddit").containsMatchIn(pkg) -> "short, natural and punchy"
        else -> "natural and clear"
    }
}
