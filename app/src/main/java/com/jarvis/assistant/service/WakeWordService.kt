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
import com.jarvis.assistant.core.WakeModel
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
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
    private var vosk: SpeechService? = null
    private var voskModel: Model? = null
    private var lastWake = 0L

    // Heard "Jarvis", or a close mishearing of it.
    private val wakeWord = Regex("\\b(jarvis|jarvas|jervis|jarvus|garvis|jar vis|jar viss)\\b")

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
        Assistant.onMicFree = { handler.postDelayed({ if (running && porcupine == null && vosk == null) beginWake() }, 500) }
        return START_STICKY
    }

    private fun beginWake(): Boolean {
        stopWake()
        // A Picovoice key, if you added one, is a little more accurate. Otherwise the free built-in model is used.
        return if (Prefs.picoKey.isNotBlank()) beginPorcupine() else beginVosk()
    }

    private fun beginPorcupine(): Boolean = try {
        porcupine = PorcupineManager.Builder()
            .setAccessKey(Prefs.picoKey)
            .setKeyword(Porcupine.BuiltInKeyword.JARVIS)
            .setSensitivity(0.7f)
            .build(applicationContext, PorcupineManagerCallback { onWake() })
        porcupine?.start()
        true
    } catch (e: Exception) {
        warn("Couldn't start wake word: " + (e.message ?: "unknown error"))
        false
    }

    private fun beginVosk(): Boolean {
        if (!WakeModel.ready(applicationContext)) {
            warn("Open Jarvis setup and download the wake-word voice model first.")
            return false
        }
        return try {
            val model = voskModel ?: Model(WakeModel.dir(applicationContext).absolutePath).also { voskModel = it }
            val service = SpeechService(Recognizer(model, 16000.0f), 16000.0f)
            service.startListening(voskListener)
            vosk = service
            true
        } catch (e: Exception) {
            warn("Couldn't start wake word: " + (e.message ?: "unknown error"))
            false
        }
    }

    private val voskListener = object : RecognitionListener {
        private fun check(json: String?) {
            if (json.isNullOrBlank()) return
            val text = try {
                val o = JSONObject(json)
                (o.optString("partial", "") + " " + o.optString("text", "")).lowercase()
            } catch (e: Exception) {
                return
            }
            val now = System.currentTimeMillis()
            if (wakeWord.containsMatchIn(text) && now - lastWake > 3000) {
                lastWake = now
                onWake()
            }
        }

        override fun onPartialResult(hypothesis: String?) = check(hypothesis)
        override fun onResult(hypothesis: String?) = check(hypothesis)
        override fun onFinalResult(hypothesis: String?) {}
        override fun onError(exception: Exception?) {}
        override fun onTimeout() {}
    }

    private fun stopWake() {
        try {
            porcupine?.stop()
            porcupine?.delete()
        } catch (_: Exception) {
        }
        porcupine = null
        try {
            vosk?.stop()
            vosk?.shutdown()
        } catch (_: Exception) {
        }
        vosk = null
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
        try {
            voskModel?.close()
        } catch (_: Exception) {
        }
        voskModel = null
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
