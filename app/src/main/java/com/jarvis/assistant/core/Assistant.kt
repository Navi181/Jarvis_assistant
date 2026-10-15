package com.jarvis.assistant.core

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

enum class Phase { IDLE, LISTENING, THINKING, SPEAKING }

data class Msg(val fromUser: Boolean, val text: String, val id: Long = System.nanoTime())

/**
 * The single shared engine: listen, think, act, speak.
 * The UI, the wake-word service and the system assistant session all drive this one object,
 * so the orb animates no matter how Jarvis was triggered. Everything here runs on the main thread.
 */
object Assistant {
    private lateinit var app: Application
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val phase = MutableStateFlow(Phase.IDLE)
    val level = MutableStateFlow(0f)          // 0..1 mic loudness for the orb
    val partial = MutableStateFlow("")        // live transcript while you speak
    val messages = MutableStateFlow<List<Msg>>(emptyList())

    /** The wake-word service sets these so only one thing owns the microphone at a time. */
    @Volatile var onMicNeeded: (() -> Unit)? = null
    @Volatile var onMicFree: (() -> Unit)? = null

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var job: Job? = null
    private var pending: Plan? = null     // a half-finished command waiting for your answer
    private var followUps = 0
    private var lastReply = ""
    private var lastKeepOpen = false

    fun init(a: Application) {
        app = a
        tts = TextToSpeech(a) { status ->
            val t = tts ?: return@TextToSpeech
            if (status == TextToSpeech.SUCCESS) {
                t.language = Locale.getDefault()
                t.setPitch(0.95f)
                t.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { main.post { afterSpeech() } }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { main.post { afterSpeech() } }
                })
                ttsReady = true
            }
        }
    }

    val busy: Boolean get() = phase.value != Phase.IDLE

    /** Start listening for a command. */
    fun listen() {
        if (phase.value == Phase.LISTENING || phase.value == Phase.THINKING) return
        tts?.stop()
        followUps = 0
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            respond("I need microphone permission. Please grant it in setup.", false)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(app)) {
            respond("Speech recognition isn't available on this phone. Install or enable the Google app.", false)
            return
        }
        phase.value = Phase.LISTENING
        partial.value = ""
        val hook = onMicNeeded
        hook?.invoke()
        // give the wake-word engine a moment to release the microphone
        main.postDelayed({ if (phase.value == Phase.LISTENING) startRecognizer() }, if (hook != null) 350L else 0L)
    }

    /** Run a typed / tapped command (suggestion chips, skill list). */
    fun submit(text: String) {
        if (phase.value == Phase.LISTENING || phase.value == Phase.THINKING) return
        tts?.stop()
        followUps = 0
        onMicNeeded?.invoke()
        handle(text)
    }

    /** Speak a message (used for study timer alerts and "read aloud"). */
    fun say(text: String) {
        if (phase.value != Phase.IDLE) return
        onMicNeeded?.invoke()
        respond(text, false)
    }

    fun cancel() {
        recognizer?.cancel()
        tts?.stop()
        job?.cancel()
        finish()
    }

    // ---------------------------------------------------------------- listening

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { phase.value = Phase.LISTENING }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) { level.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { level.value = 0f }
        override fun onPartialResults(partialResults: Bundle?) {
            partial.value = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
        }
        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            if (text.isEmpty()) finish() else handle(text)
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                SpeechRecognizer.ERROR_CLIENT -> finish()
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    respond("I need microphone permission. Please grant it in setup.", false)
                else -> respond("I couldn't hear you properly. Check your internet connection and try again.", false)
            }
        }
    }

    private fun startRecognizer() {
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(app)
        recognizer = r
        r.setRecognitionListener(listener)
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
        }
        partial.value = ""
        phase.value = Phase.LISTENING
        r.startListening(i)
    }

    // ---------------------------------------------------------------- thinking + acting

    private fun handle(text: String) {
        partial.value = ""
        level.value = 0f
        val history = messages.value.takeLast(6)
        messages.value = (messages.value + Msg(true, text)).takeLast(30)
        phase.value = Phase.THINKING
        val waiting = pending
        pending = null
        job?.cancel()
        job = scope.launch {
            val out = try {
                val plan = waiting?.let { LocalParser.complete(it, text) } ?: Brain.plan(text, history)
                Actions.run(app, plan)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Outcome("Sorry, something went wrong.")
            }
            pending = out.awaiting
            respond(out.reply, out.keepOpen)
        }
    }

    // ---------------------------------------------------------------- speaking

    private fun respond(text: String, keepOpen: Boolean) {
        if (text.isBlank()) {
            finish()
            return
        }
        messages.value = (messages.value + Msg(false, text)).takeLast(30)
        lastReply = text
        lastKeepOpen = keepOpen
        phase.value = Phase.SPEAKING
        val t = tts
        if (t != null && ttsReady) {
            t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
        } else {
            main.postDelayed({ afterSpeech() }, 1200)
        }
    }

    private fun afterSpeech() {
        if (phase.value != Phase.SPEAKING) return
        // Keep the mic open when Jarvis asked you something, or after an answer in conversation mode.
        val more = pending != null || lastReply.trimEnd().endsWith("?") || (Prefs.continuous && lastKeepOpen)
        if (more && followUps < 4) {
            followUps++
            startRecognizer()
        } else {
            finish()
        }
    }

    private fun finish() {
        phase.value = Phase.IDLE
        level.value = 0f
        partial.value = ""
        followUps = 0
        pending = null
        onMicFree?.invoke()
    }
}
