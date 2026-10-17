package com.jarvis.assistant.system

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.jarvis.assistant.MainActivity
import com.jarvis.assistant.core.FocusLock
import com.jarvis.assistant.core.WhatsAppSender

/**
 * Two small jobs, both optional and only while you ask for them:
 *  1. During a focus session, if another app comes to the front, bring Jarvis back.
 *  2. After "message X on WhatsApp", press WhatsApp's Send button for you.
 * Nothing is read or stored; it only looks for the Send button and the app that is in front.
 */
class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: JarvisAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    // ------------------------------------------------------------------ screen control (used by the phone agent)

    class Item(val node: AccessibilityNodeInfo, val label: String, val line: String)

    /** Everything useful on the screen in front, numbered. */
    fun items(): Pair<String, List<Item>> {
        val root = rootInActiveWindow ?: return "" to emptyList()
        val out = ArrayList<Item>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 30 || out.size >= 120 || !n.isVisibleToUser) return
            val text = n.text?.toString().orEmpty().trim()
            val desc = n.contentDescription?.toString().orEmpty().trim()
            val hint = if (Build.VERSION.SDK_INT >= 26) n.hintText?.toString().orEmpty() else ""
            val label = listOf(text, desc, hint).firstOrNull { it.isNotBlank() }.orEmpty().replace("\n", " ").take(80)
            val editable = n.isEditable
            if (label.isNotBlank() || editable || n.isClickable || n.isScrollable) {
                val flags = buildList {
                    if (n.isClickable) add("tap")
                    if (editable) add("type")
                    if (n.isScrollable) add("scroll")
                    if (n.isChecked) add("on")
                }.joinToString(",")
                out.add(Item(n, label, "[${out.size}] ${n.className?.toString()?.substringAfterLast('.').orEmpty()} \"$label\" $flags".trim()))
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return (root.packageName?.toString().orEmpty()) to out
    }

    private fun tapAncestor(n: AccessibilityNodeInfo): Boolean {
        var c: AccessibilityNodeInfo? = n
        var d = 0
        while (c != null && d < 6) {
            if (c.isClickable && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            c = c.parent
            d++
        }
        return false
    }

    private fun tapAt(n: AccessibilityNodeInfo): Boolean {
        val r = android.graphics.Rect()
        n.getBoundsInScreen(r)
        val path = android.graphics.Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val g = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 80)).build()
        return dispatchGesture(g, null, null)
    }

    fun click(item: Item): Boolean = tapAncestor(item.node) || tapAt(item.node)

    fun type(item: Item, text: String, submit: Boolean): Boolean {
        item.node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = android.os.Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        val ok = item.node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok && submit && Build.VERSION.SDK_INT >= 30) {
            item.node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        return ok
    }

    fun scroll(items: List<Item>, down: Boolean): Boolean {
        val n = items.firstOrNull { it.node.isScrollable }?.node ?: return false
        return n.performAction(if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }

    fun global(name: String): Boolean = when (name) {
        "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
        "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
        "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        "quick_settings" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        else -> false
    }

    private val handler = Handler(Looper.getMainLooper())
    private var sending = false
    private var tries = 0
    private var picked = false
    private var lastBringBack = 0L

    private val allowed = listOf(
        "com.jarvis.assistant", "com.android.systemui", "android", "com.android.incallui",
        "com.samsung.android.incallui", "com.google.android.dialer", "com.android.phone", "com.android.server.telecom"
    )

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        val pkg = e?.packageName?.toString() ?: return

        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && FocusLock.active() && isOutsider(pkg)) {
            bringBack()
        }
        if ((pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b") && WhatsAppSender.armed()) {
            scheduleSend()
        }
    }

    private fun isOutsider(pkg: String): Boolean {
        if (pkg in allowed) return false
        val p = pkg.lowercase()
        // keyboards also report a window change when they open
        if ("inputmethod" in p || "keyboard" in p || "honeyboard" in p) return false
        return true
    }

    private fun bringBack() {
        val now = System.currentTimeMillis()
        if (now - lastBringBack < 800) return
        lastBringBack = now
        try {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            )
        } catch (ex: Exception) {
            // not allowed to start from the background on this phone; screen pinning still covers it
        }
    }

    // ------------------------------------------------------------------ WhatsApp send

    private val sendRunnable = object : Runnable {
        override fun run() {
            if (!WhatsAppSender.armed()) {
                sending = false
                return
            }
            val root = rootInActiveWindow
            val wanted = WhatsAppSender.group
            if (wanted.isNotEmpty() && !picked) {
                if (root != null && pickGroup(root, wanted)) picked = true
                tries++
                if (tries < 65) handler.postDelayed(this, 600) else sending = false
                return
            }
            val button = if (root != null) findSend(root) else null
            if (button != null && button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                WhatsAppSender.disarm()
                sending = false
                picked = false
                return
            }
            tries++
            if (tries < (if (wanted.isNotEmpty()) 30 else 14)) handler.postDelayed(this, 500) else sending = false
        }
    }

    private fun scheduleSend() {
        if (sending) return
        sending = true
        tries = 0
        picked = false
        handler.postDelayed(sendRunnable, 800)
    }

    private fun findSend(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (id in listOf("com.whatsapp:id/send", "com.whatsapp.w4b:id/send")) {
            val hits = root.findAccessibilityNodeInfosByViewId(id)
            if (hits != null) {
                for (n in hits) if (n.isEnabled) return n
            }
        }
        return walk(root, 0)
    }

    /** Fallback for phones set to English: the button is described as "Send". */
    private fun walk(n: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (n == null || depth > 25) return null
        if (n.isClickable && n.contentDescription?.toString().equals("Send", ignoreCase = true)) return n
        for (i in 0 until n.childCount) {
            val r = walk(n.getChild(i), depth + 1)
            if (r != null) return r
        }
        return null
    }

    // ------------------------------------------------------------------ WhatsApp group picking

    private fun clickable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var c = n
        var d = 0
        while (c != null && d < 8) {
            if (c.isClickable) return c
            c = c.parent
            d++
        }
        return null
    }

    private fun findEdit(n: AccessibilityNodeInfo?, depth: Int = 0): AccessibilityNodeInfo? {
        if (n == null || depth > 25) return null
        if (n.className?.toString() == "android.widget.EditText") return n
        for (i in 0 until n.childCount) findEdit(n.getChild(i), depth + 1)?.let { return it }
        return null
    }

    private fun findSearch(n: AccessibilityNodeInfo?, depth: Int = 0): AccessibilityNodeInfo? {
        if (n == null || depth > 25) return null
        if (n.isClickable && n.contentDescription?.toString().equals("Search", ignoreCase = true)) return n
        for (i in 0 until n.childCount) findSearch(n.getChild(i), depth + 1)?.let { return it }
        return null
    }

    /** One step per call: tap the group if it is on screen, else open search and type its name. Returns true once tapped. */
    private fun pickGroup(root: AccessibilityNodeInfo, name: String): Boolean {
        val hits = root.findAccessibilityNodeInfosByText(name)
        if (hits != null) {
            val row = hits.firstOrNull {
                it.className?.toString() != "android.widget.EditText" && it.text?.toString().equals(name, ignoreCase = true)
            } ?: hits.firstOrNull { it.className?.toString() != "android.widget.EditText" }
            val target = clickable(row)
            if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        val edit = findEdit(root)
        if (edit != null) {
            if (edit.text?.toString() != name) {
                val args = android.os.Bundle()
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, name)
                edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
            return false
        }
        findSearch(root)?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        return false
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
