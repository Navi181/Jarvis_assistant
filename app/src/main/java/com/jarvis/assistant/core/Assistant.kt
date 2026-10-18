package com.jarvis.assistant.core

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
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

    /** A video / photo / audio / PDF attached to the chat; every question (typed or spoken) is about it until removed. */
    val media = MutableStateFlow<MediaAsk.Attachment?>(null)

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
        val google = try { a.packageManager.getPackageInfo("com.google.android.tts", 0); "com.google.android.tts" } catch (e: Exception) { null }
        tts = TextToSpeech(a, { status ->
            val t = tts ?: return@TextToSpeech
            if (status == TextToSpeech.SUCCESS) {
                if (t.setLanguage(Locale.forLanguageTag(Prefs.language)) < TextToSpeech.LANG_AVAILABLE) t.language = Locale.getDefault()
                applyVoice(t)
                t.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { main.post { if (id == "jarvis_syn") synthDone?.invoke(true) else afterSpeech() } }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { main.post { if (id == "jarvis_syn") synthDone?.invoke(false) else afterSpeech() } }
                })
                ttsReady = true
            }
        }, google)
    }

    // ---------------------------------------------------------------- voice

    /** Voices that Google's speech engine offers for the phone language (or English), in a stable order. */
    private fun voiceChoices(t: TextToSpeech): List<android.speech.tts.Voice> = try {
        val lang = Prefs.language.substringBefore('-')
        t.voices.orEmpty().filter { it.locale.language == lang || it.locale.language == "en" }.sortedBy { it.name }
    } catch (e: Exception) {
        emptyList()
    }

    // Google voice ids that are male, best first. Used when the user hasn't picked one.
    private val maleHints = listOf(
        "en-gb-x-gbd-network", "en-gb-x-rjs-network", "en-gb-x-gbd-local", "en-gb-x-rjs-local", "en-gb-x-gbb-network", "en-gb-x-gbb-local",
        "en-in-x-end-network", "en-in-x-end-local", "en-in-x-ahp-local",
        "en-us-x-iom-network", "en-us-x-iom-local", "en-us-x-iol-local", "en-us-x-tpd-local", "en-us-x-iob-local"
    )

    private fun applyVoice(t: TextToSpeech) {
        try {
            if (t.setLanguage(Locale.forLanguageTag(Prefs.language)) < TextToSpeech.LANG_AVAILABLE) t.language = Locale.getDefault()
        } catch (_: Exception) {
        }
        try {
            t.setPitch(if (Prefs.deepVoice) 1.0f else Prefs.voicePitch)
            t.setSpeechRate(Prefs.voiceRate)
            val all = voiceChoices(t)
            val chosen = all.firstOrNull { it.name == Prefs.voiceName }
                ?: maleHints.firstNotNullOfOrNull { h -> all.firstOrNull { it.name == h } }
                ?: all.firstOrNull { it.name.lowercase().let { n -> "male" in n && "female" !in n } }
            if (chosen != null) t.setVoice(chosen)
        } catch (e: Exception) {
            // keep the default voice
        }
    }

    /** Re-reads pitch and speed from settings (after moving the slider). */
    fun applyVoiceSettings() {
        tts?.let { applyVoice(it) }
    }

    /** Switches to the next installed voice so you can find one you like by ear. Returns a label. */
    fun cycleVoice(): String {
        if (GeminiTts.available()) {
            val names = GeminiTts.voices
            val next = names[(names.indexOf(Extras.aiVoiceName.ifBlank { names[0] }) + 1) % names.size]
            Extras.aiVoiceName = next
            return "Jarvis voice: $next (${names.indexOf(next) + 1} of ${names.size})"
        }
        val t = tts ?: return "Voice not ready yet"
        val list = voiceChoices(t)
        if (list.size < 2) return "Only one voice on this phone. I deepen it for you. Install Speech Services by Google for more."
        val cur = try { t.voice?.name } catch (e: Exception) { null }
        val next = list[(list.indexOfFirst { it.name == cur } + 1) % list.size]
        try {
            t.setVoice(next)
            t.setPitch(if (Prefs.deepVoice) 1.0f else Prefs.voicePitch)
        } catch (e: Exception) {
            return "That voice didn't work"
        }
        Prefs.voiceName = next.name
        return "Voice ${list.indexOf(next) + 1} of ${list.size}"
    }

    fun previewVoice() {
        stopSpeech()
        say("Good day. I'm Jarvis, at your service.")
    }

    val busy: Boolean get() = phase.value != Phase.IDLE

    /** Start listening for a command. */
    fun listen() {
        if (phase.value == Phase.LISTENING || phase.value == Phase.THINKING) return
        stopSpeech()
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
        stopSpeech()
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
        stopSpeech()
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
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    // keep listening for about 10 seconds in total, then give up quietly
                    if (phase.value == Phase.LISTENING && System.currentTimeMillis() - listenStart < 9500L) main.post { if (phase.value == Phase.LISTENING) startRecognizer(false) } else finish()
                }
                SpeechRecognizer.ERROR_CLIENT -> finish()
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    respond("I need microphone permission. Please grant it in setup.", false)
                else -> respond("I couldn't hear you properly. Check your internet connection and try again.", false)
            }
        }
    }

    @Volatile private var listenStart = 0L

    private fun startRecognizer(fresh: Boolean = true) {
        if (fresh) listenStart = System.currentTimeMillis()
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(app)
        recognizer = r
        r.setRecognitionListener(listener)
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Prefs.language)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
        }
        partial.value = ""
        phase.value = Phase.LISTENING
        r.startListening(i)
    }

    // ---------------------------------------------------------------- thinking + acting

    fun attach(a: MediaAsk.Attachment?) {
        MediaAsk.forget()
        media.value = a
    }

    /** Ask about the attached file. A blank question gets a general description. */
    fun submitMedia(question: String) {
        val a = media.value ?: return
        if (phase.value == Phase.LISTENING || phase.value == Phase.THINKING) return
        stopSpeech()
        followUps = 0
        onMicNeeded?.invoke()
        handle(question.ifBlank { MediaAsk.defaultQuestion(a) })
    }

    private fun mediaClear(text: String) =
        Regex("^(?:please )?(?:remove|clear|forget|close|detach|delete|drop|stop using)(?: about)? (?:the |this |that )?(?:video|photo|picture|image|attachment|file|clip|pdf|audio)\\b.*").matches(text.lowercase().trim())

    private fun handleMedia(a: MediaAsk.Attachment, text: String) {
        if (mediaClear(text)) {
            attach(null)
            messages.value = (messages.value + Msg(true, text)).takeLast(30)
            pending = null
            respond("Done, I've let go of the ${a.kind}.", false)
            return
        }
        messages.value = (messages.value + Msg(true, text)).takeLast(30)
        phase.value = Phase.THINKING
        pending = null
        job?.cancel()
        job = scope.launch {
            val reply = try {
                kotlinx.coroutines.withContext(Dispatchers.IO) { MediaAsk.analyze(app, a, text, Brain.wantsDetail(text)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                MediaAsk.friendly(e)
            }
            respond(reply, false)
        }
    }

    private fun handle(text: String) {
        partial.value = ""
        level.value = 0f
        val attached = media.value
        if (attached != null && pending == null) {
            // questions go to the attached file; clear device commands ("call mom", "set a timer") still work as usual
            val device = LocalParser.parse(text)?.action
            if (device == null || device in setOf("answer", "elaborate", "web_answer") || mediaClear(text)) {
                handleMedia(attached, text)
                return
            }
        }
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
        val say = SpeechText.clean(text).let { SpeechText.limit(it, 2400) }
        val token = ++speakToken
        if (GeminiTts.available() && say.length <= 420) {
            // the Jarvis voice (Gemini, deep British male); the phone's voice covers it if this fails
            Thread {
                val pcm = GeminiTts.synth(say)
                main.post {
                    if (token != speakToken || phase.value != Phase.SPEAKING) return@post
                    if (pcm != null && playPcm(pcm.first, pcm.second)) return@post
                    if (t != null && ttsReady) speakLocal(t, say) else afterSpeech()
                }
            }.start()
        } else if (t != null && ttsReady) {
            speakLocal(t, say)
        } else {
            main.postDelayed({ afterSpeech() }, 1200)
        }
    }

    private var speakToken = 0

    private fun speakLocal(t: TextToSpeech, say: String) {
        if (!(Prefs.deepVoice && speakDeep(t, say))) t.speak(say, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
    }

    private fun playPcm(b: ByteArray, rate: Int): Boolean = try {
        val len = b.size - b.size % 2
        if (len <= 0 || rate <= 0) {
            false
        } else {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                )
                .setAudioFormat(
                    AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                )
                .setBufferSizeInBytes(len)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(b, 0, len)
            player = track
            track.play()
            main.postDelayed(playEnd, len / 2 * 1000L / rate + 300L)
            true
        }
    } catch (e: Exception) {
        false
    }

    // ---------------------------------------------------------------- deep voice
    // The phone's voice is rendered to a sound file, then played back at a lower pitch without slowing down.
    // This works with any speech engine, so a female-sounding voice comes out as a deep male one.

    private var synthDone: ((Boolean) -> Unit)? = null
    private var player: AudioTrack? = null
    private val playEnd = Runnable {
        try { player?.release() } catch (_: Exception) {}
        player = null
        afterSpeech()
    }

    private fun stopSpeech() {
        speakToken++
        tts?.stop()
        synthDone = null
        main.removeCallbacks(playEnd)
        try { player?.stop(); player?.release() } catch (_: Exception) {}
        player = null
    }

    private fun speakDeep(t: TextToSpeech, text: String): Boolean = try {
        val f = java.io.File(app.cacheDir, "say.wav")
        f.delete()
        synthDone = { ok ->
            synthDone = null
            if (phase.value == Phase.SPEAKING) {
                if (!(ok && playWav(f))) t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
            }
        }
        t.synthesizeToFile(text.take(3800), Bundle(), f, "jarvis_syn") == TextToSpeech.SUCCESS
    } catch (e: Exception) {
        false
    }

    private fun playWav(f: java.io.File): Boolean = try {
        val b = f.readBytes()
        fun le16(o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
        fun le32(o: Int) = le16(o) or (le16(o + 2) shl 16)
        var pos = 12
        var rate = 22050
        var ch = 1
        var bits = 16
        var off = -1
        var len = 0
        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4, Charsets.US_ASCII)
            var n = le32(pos + 4)
            if (id == "fmt ") {
                ch = le16(pos + 10); rate = le32(pos + 12); bits = le16(pos + 22)
            } else if (id == "data") {
                off = pos + 8
                if (n <= 0 || off + n > b.size) n = b.size - off
                len = n
                break
            }
            pos += 8 + n + (n and 1)
        }
        if (off < 0 || bits != 16 || len <= 0 || rate <= 0) {
            false
        } else {
            len -= len % (2 * ch)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                )
                .setAudioFormat(
                    AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate)
                        .setChannelMask(if (ch == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO).build()
                )
                .setBufferSizeInBytes(len)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(b, off, len)
            track.playbackParams = PlaybackParams().setSpeed(1f).setPitch(Prefs.voicePitch.coerceIn(0.4f, 1.2f))
            player = track
            track.play()
            val ms = len / (2 * ch) * 1000L / rate
            main.postDelayed(playEnd, ms + 300L)
            true
        }
    } catch (e: Exception) {
        false
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
