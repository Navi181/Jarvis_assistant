package com.jarvis.assistant.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
import com.jarvis.assistant.MainActivity
import com.jarvis.assistant.R
import com.jarvis.assistant.core.Assistant
import com.jarvis.assistant.core.Prefs

/**
 * Hands-free mode. Runs Picovoice Porcupine on-device (no audio leaves the phone while waiting),
 * and when it hears "Jarvis" it hands the mic to the speech recogniser, runs the command,
 * speaks the answer, then goes back to waiting.
 */
class WakeWordService : Service() {

    companion object {
        private const val CHANNEL = "jarvis_wake"
        private const val NOTIF_ID = 42

        @Volatile
        var running = false
            private set

        fun start(ctx: Context) {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
            ContextCompat.startForegroundService(ctx, Intent(ctx, WakeWordService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, WakeWordService::class.java))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var porcupine: PorcupineManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val n = notification("Say “Jarvis” to wake me")
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!beginWake()) {
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        Assistant.onMicNeeded = { handler.post { stopWake() } }
        Assistant.onMicFree = { handler.postDelayed({ if (running && porcupine == null) beginWake() }, 500) }
        return START_STICKY
    }

    private fun beginWake(): Boolean {
        stopWake()
        val key = Prefs.picoKey
        if (key.isBlank()) {
            warn("Add your free Picovoice key in Jarvis setup to enable hands-free.")
            return false
        }
        return try {
            porcupine = PorcupineManager.Builder()
                .setAccessKey(key)
                .setKeyword(Porcupine.BuiltInKeyword.JARVIS)
                .setSensitivity(0.7f)
                .build(applicationContext, PorcupineManagerCallback { onWake() })
            porcupine?.start()
            true
        } catch (e: Exception) {
            warn("Couldn't start wake word: " + (e.message ?: "unknown error"))
            false
        }
    }

    private fun stopWake() {
        try {
            porcupine?.stop()
            porcupine?.delete()
        } catch (_: Exception) {
        }
        porcupine = null
    }

    /** Called on Porcupine's audio thread. */
    private fun onWake() {
        handler.post {
            try {
                ToneGenerator(AudioManager.STREAM_MUSIC, 70).startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            } catch (_: Exception) {
            }
            Assistant.listen() // the mic hooks below pause/resume wake-word detection around the conversation
        }
    }

    override fun onDestroy() {
        running = false
        Assistant.onMicNeeded = null
        Assistant.onMicFree = null
        handler.removeCallbacksAndMessages(null)
        stopWake()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ notification

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Hands-free", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Jarvis")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun warn(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text))
    }
}
