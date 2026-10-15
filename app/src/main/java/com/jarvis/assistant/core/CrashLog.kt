package com.jarvis.assistant.core

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Date

/** Saves the last crash to a file so you can share it and it gets fixed. Nothing is sent automatically. */
object CrashLog {
    private fun file(ctx: Context) = File(ctx.filesDir, "crash.txt")

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                file(app).writeText(
                    "Jarvis crash report\n" +
                        "Time: ${Date()}\n" +
                        "Build: ${Updater.versionLabel(app)}\n" +
                        "Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
                        "Thread: ${thread.name}\n\n$sw"
                )
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, e)
        }
    }

    fun last(ctx: Context): String? = try { file(ctx).takeIf { it.exists() }?.readText() } catch (e: Exception) { null }

    fun clear(ctx: Context) { try { file(ctx).delete() } catch (_: Exception) { } }

    fun share(ctx: Context) {
        val text = last(ctx) ?: return
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Jarvis crash report")
            .putExtra(Intent.EXTRA_TEXT, text.take(60_000))
        ctx.startActivity(Intent.createChooser(send, "Share crash report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
