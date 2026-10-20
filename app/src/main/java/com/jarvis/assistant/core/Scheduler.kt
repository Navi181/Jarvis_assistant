package com.jarvis.assistant.core

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.jarvis.assistant.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Calendar

/**
 * Things that happen by themselves at a set time:
 *  - a routine that runs every day ("run good night every day at 10 pm")
 *  - a reminder that keeps coming back until you say done ("nag me to drink water")
 * Uses alarm-clock style alarms, which Android allows without any special permission (a small alarm icon shows while one is set).
 */
object Scheduler {
    data class Sched(val name: String, val hour: Int, val minute: Int)

    private const val RUN = "com.jarvis.assistant.RUN_ROUTINE"
    private const val NAG = "com.jarvis.assistant.NAG"
    private const val NAG_DONE = "com.jarvis.assistant.NAG_DONE"
    private const val CHANNEL = "jarvis_nag"

    // ---------------------------------------------------------------- stored schedules
    fun list(): List<Sched> = try {
        Prefs.rawSchedules().split('\u001F').filter { it.count { c -> c == '|' } == 2 }.map {
            val (n, h, m) = it.split('|')
            Sched(n, h.toInt(), m.toInt())
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun save(l: List<Sched>) = Prefs.saveRawSchedules(l.joinToString("\u001F") { "${it.name}|${it.hour}|${it.minute}" })

    fun add(ctx: Context, s: Sched) {
        save(list().filter { it.name.lowercase() != s.name.lowercase() } + s)
        schedule(ctx, s)
        JarvisWidget.refresh(ctx)
    }

    fun remove(ctx: Context, name: String): Boolean {
        val all = list()
        val hit = all.filter { it.name.lowercase() == name.lowercase().trim() }
        if (hit.isEmpty()) return false
        hit.forEach { cancel(ctx, it) }
        save(all - hit.toSet())
        JarvisWidget.refresh(ctx)
        return true
    }

    fun scheduleAll(ctx: Context) = list().forEach { schedule(ctx, it) }

    // ---------------------------------------------------------------- alarms
    private fun alarms(ctx: Context) = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun showIntent(ctx: Context) =
        PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun fire(ctx: Context, action: String, key: String, fill: Intent.() -> Unit): PendingIntent {
        val i = Intent(ctx, ScheduleReceiver::class.java).setAction(action).setData(Uri.parse("jarvis://$action/${Uri.encode(key)}"))
        i.fill()
        return PendingIntent.getBroadcast(ctx, key.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun next(h: Int, m: Int): Long {
        val c = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        if (c.timeInMillis <= System.currentTimeMillis() + 5_000) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    fun schedule(ctx: Context, s: Sched) {
        try {
            val pi = fire(ctx, RUN, s.name) { putExtra("name", s.name) }
            alarms(ctx).setAlarmClock(AlarmManager.AlarmClockInfo(next(s.hour, s.minute), showIntent(ctx)), pi)
        } catch (_: Exception) {
        }
    }

    private fun cancel(ctx: Context, s: Sched) {
        try { alarms(ctx).cancel(fire(ctx, RUN, s.name) { putExtra("name", s.name) }) } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------- nagging reminders
    fun startNag(ctx: Context, msg: String, everyMin: Int, firstInMin: Int = 1) {
        Prefs.nagMessage = msg
        Prefs.nagCount = 0
        setNag(ctx, msg, everyMin, firstInMin)
        JarvisWidget.refresh(ctx)
    }

    private fun setNag(ctx: Context, msg: String, everyMin: Int, inMin: Int) {
        try {
            val pi = fire(ctx, NAG, "nag") { putExtra("msg", msg); putExtra("every", everyMin) }
            alarms(ctx).setAlarmClock(AlarmManager.AlarmClockInfo(System.currentTimeMillis() + inMin * 60_000L, showIntent(ctx)), pi)
        } catch (_: Exception) {
        }
    }

    fun stopNags(ctx: Context): Boolean {
        val was = Prefs.nagMessage.isNotBlank()
        Prefs.nagMessage = ""
        Prefs.nagCount = 0
        try { alarms(ctx).cancel(fire(ctx, NAG, "nag") { }) } catch (_: Exception) {}
        try { (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(4242) } catch (_: Exception) {}
        JarvisWidget.refresh(ctx)
        return was
    }

    internal fun onNag(ctx: Context, msg: String, every: Int) {
        if (Prefs.nagMessage.isBlank()) return                    // you already said done
        Prefs.nagCount = Prefs.nagCount + 1
        notifyNag(ctx, msg)
        Handler(Looper.getMainLooper()).postDelayed({ Assistant.say("Reminder: $msg. Say done when it's finished.") }, 1500)
        if (Prefs.nagCount < 12) setNag(ctx, msg, every, every) else stopNags(ctx)   // never more than 12 times
    }

    private fun notifyNag(ctx: Context, msg: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH))
        val done = fire(ctx, NAG_DONE, "nagdone") { }
        val n = androidx.core.app.NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(com.jarvis.assistant.R.drawable.ic_notification)
            .setContentTitle("Reminder")
            .setContentText(msg)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(showIntent(ctx))
            .addAction(0, "Done", done)
            .setAutoCancel(true)
            .build()
        try { nm.notify(4242, n) } catch (_: SecurityException) {}
    }

    internal fun runRoutine(ctx: Context, name: String, pr: BroadcastReceiver.PendingResult) {
        CoroutineScope(Dispatchers.Default).launch {
            try {
                withTimeoutOrNull(9_000) {
                    val out = Actions.run(ctx.applicationContext, Plan("routine_run", target = name))
                    Handler(Looper.getMainLooper()).post { if (out.reply.isNotBlank()) Assistant.say(out.reply) }
                }
            } finally {
                pr.finish()
            }
        }
    }

    internal fun done(ctx: Context) {
        stopNags(ctx)
    }

    internal fun nextFor(name: String): Sched? = list().firstOrNull { it.name == name }
}

/** Receives the alarms above, and the "phone restarted" signal so schedules survive a reboot. */
class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        when (i.action) {
            "com.jarvis.assistant.RUN_ROUTINE" -> {
                val name = i.getStringExtra("name").orEmpty()
                Scheduler.nextFor(name)?.let { Scheduler.schedule(ctx, it) }   // set tomorrow's run first
                Scheduler.runRoutine(ctx, name, goAsync())
            }
            "com.jarvis.assistant.NAG" -> Scheduler.onNag(ctx, i.getStringExtra("msg").orEmpty(), i.getIntExtra("every", 5))
            "com.jarvis.assistant.NAG_DONE" -> Scheduler.done(ctx)
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED) {
            Prefs.init(ctx.applicationContext)
            Scheduler.scheduleAll(ctx)
        }
    }
}
