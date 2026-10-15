package com.jarvis.assistant.service

import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Lets Jarvis read your notifications aloud ("any new messages?"). Granted by you in Android settings. */
class JarvisNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() { instance = this }
    override fun onListenerDisconnected() { if (instance === this) instance = null }

    companion object {
        @Volatile
        var instance: JarvisNotificationListener? = null

        val connected: Boolean get() = instance != null

        /** Spoken one-liners like "WhatsApp, Priya: running late". Newest first, max [limit]. */
        fun summaries(ctx: Context, limit: Int = 5): List<String>? {
            val svc = instance ?: return null
            val pm = ctx.packageManager
            val list = try { svc.activeNotifications?.toList().orEmpty() } catch (e: Exception) { return null }
            return list
                .filter { it.packageName != ctx.packageName && !it.isOngoing && (it.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0 }
                .sortedByDescending { it.postTime }
                .mapNotNull { sbn -> describe(pm, sbn) }
                .take(limit)
        }

        private fun describe(pm: PackageManager, sbn: StatusBarNotification): String? {
            val ex = sbn.notification.extras
            val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
            if (title.isBlank() && text.isBlank()) return null
            val app = try {
                pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString()
            } catch (e: Exception) { "An app" }
            return listOf(app, listOf(title, text.take(140)).filter { it.isNotBlank() }.joinToString(": ")).joinToString(", ")
        }
    }
}
