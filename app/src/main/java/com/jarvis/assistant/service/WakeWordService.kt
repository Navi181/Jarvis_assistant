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
    private var google: android.speech.SpeechRecognizer? = null
    private var googleOn = false

    // Heard "Jarvis", or a close mishearing of it.
    // The small offline model doesn't know the word "Jarvis", so it writes down what it sounds like.
    private val wakeWord = Regex("\\b(jarvis|jarvas|jervis|jarvus|garvis|harvis|charvis|darvis|travis|jarves|jar vis+|jar viss?|jar bus|jar bis|jar vice|java is)\\b")

    private fun isWake(text: String): Boolean {
        val t = text.lowercase().trim()
        if (t.isBlank()) return false
        if (wakeWord.containsMatchIn(t)) return true
        val words = t.split(Regex("\\s+"))
        return words.size <= 2 && ("service" in words || "jarvis's" in words)
    }

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
        Assistant.onMicFree = { handler.postDelayed({ if (running && porcupine == null && vosk == null && !googleOn) beginWake() }, 500) }
        return START_STICKY
    }

    private fun beginWake(): Boolean {
        stopWake()
        // A Picovoice key, if you added one, is a little more accurate. Otherwise the free built-in model is used.
        if (Prefs.picoKey.isNotBlank()) return beginPorcupine()
        // The free offline model if it's been downloaded, otherwise Google's own recogniser (no download needed).
        if (WakeModel.ready(applicationContext) && beginVosk()) return true
        return beginGoogle()
    }

    private fun beginGoogle(): Boolean {
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(applicationContext)) {
            warn("Speech recognition isn't available. Install or enable the Google app.")
            return false
        }
        googleOn = true
        handler.post { googleListen() }
        return true
    }

    private val googleListener = object : android.speech.RecognitionListener {
        private fun check(b: android.os.Bundle?) {
            val list = b?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION) ?: return
            val now = System.currentTimeMillis()
            if (list.any { isWake(it) } && now - lastWake > 3000) {
                lastWake = now
                onWake()
            }
        }

        override fun onPartialResults(partialResults: android.os.Bundle?) = check(partialResults)
        override fun onResults(results: android.os.Bundle?) {
            check(results)
            again()
        }

        override fun onError(error: Int) = again()
        override fun onReadyForSpeech(params: android.os.Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: android.os.Bundle?) {}

        private fun again() {
            if (googleOn) handler.postDelayed({ googleListen() }, 400)
        }
    }

    private fun googleListen() {
        if (!googleOn || !running) return
        try {
            google?.destroy()
            val r = android.speech.SpeechRecognizer.createSpeechRecognizer(applicationContext)
            r.setRecognitionListener(googleListener)
            r.startListening(
                Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            )
            google = r
        } catch (e: Exception) {
            handler.postDelayed({ googleListen() }, 1500)
        }
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
            if (isWake(text) && now - lastWake > 3000) {
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
        googleOn = false
        try {
            google?.cancel()
            google?.destroy()
        } catch (_: Exception) {
        }
        google = null
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
