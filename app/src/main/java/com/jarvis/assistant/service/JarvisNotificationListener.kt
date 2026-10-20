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

        class Replyable(val app: String, val sender: String, val action: Notification.Action)

        /** The newest message notification that has a Reply box (WhatsApp, SMS, Telegram...). [who] narrows it to one sender. */
        fun latestReplyable(ctx: Context, who: String = ""): Replyable? {
            val svc = instance ?: return null
            val list = try { svc.activeNotifications?.toList().orEmpty() } catch (e: Exception) { return null }
            val pm = ctx.packageManager
            for (sbn in list.filter { it.packageName != ctx.packageName }.sortedByDescending { it.postTime }) {
                val act = sbn.notification.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true } ?: continue
                val title = sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
                if (who.isNotBlank() && !title.lowercase().contains(who.lowercase().trim())) continue
                val app = try { pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString() } catch (e: Exception) { "that app" }
                return Replyable(app, title.ifBlank { "this chat" }, act)
            }
            return null
        }

        /** Types the reply into the notification's own Reply box and sends it. */
        fun sendReply(r: Replyable, message: String): Boolean = try {
            val inputs = r.action.remoteInputs
            val ri = inputs.first { it.allowFreeFormInput }
            val data = android.os.Bundle().also { it.putCharSequence(ri.resultKey, message) }
            val fill = android.content.Intent()
            android.app.RemoteInput.addResultsToIntent(inputs, fill, data)
            r.action.actionIntent.send(instance ?: throw IllegalStateException(), 0, fill)
            true
        } catch (e: Exception) {
            false
        }

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
