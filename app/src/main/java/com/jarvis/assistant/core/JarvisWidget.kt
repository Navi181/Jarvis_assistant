package com.jarvis.assistant.core

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.jarvis.assistant.MainActivity
import com.jarvis.assistant.R

/** Home-screen widget: tap to talk, and it shows what is coming up next. */
class JarvisWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { mgr.updateAppWidget(it, build(ctx)) }
    }

    companion object {
        private fun nextLine(): String = try {
            val nag = Prefs.nagMessage
            if (nag.isNotBlank()) "Reminding you to $nag"
            else {
                val now = java.time.LocalTime.now().let { it.hour * 60 + it.minute }
                val l = Scheduler.list().sortedBy { (it.hour * 60 + it.minute - now + 1440) % 1440 }
                l.firstOrNull()?.let { "Next: ${it.name} at " + String.format("%02d:%02d", it.hour, it.minute) } ?: "Tap to talk"
            }
        } catch (e: Exception) { "Tap to talk" }

        fun build(ctx: Context): RemoteViews {
            try { Prefs.init(ctx.applicationContext) } catch (e: Exception) { }
            val v = RemoteViews(ctx.packageName, R.layout.widget_jarvis)
            val i = Intent(ctx, MainActivity::class.java).putExtra("listen", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pi = PendingIntent.getActivity(ctx, 4242, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            v.setOnClickPendingIntent(R.id.root, pi)
            v.setOnClickPendingIntent(R.id.mic, pi)
            v.setTextViewText(R.id.next, nextLine())
            return v
        }

        /** Called whenever schedules or reminders change. */
        fun refresh(ctx: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(ctx)
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, JarvisWidget::class.java))
                ids.forEach { mgr.updateAppWidget(it, build(ctx)) }
            } catch (e: Exception) { }
        }
    }
}
