package com.jarvis.assistant.system

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import androidx.core.content.ContextCompat
import com.jarvis.assistant.MainActivity
import com.jarvis.assistant.core.Prefs
import com.jarvis.assistant.service.WakeWordService

/**
 * Registers Jarvis as a real "Digital assistant app" (Settings > Apps > Default apps).
 * Once selected, the power-button long-press / corner swipe / headset button open Jarvis.
 * On boot the system binds this service, which also revives hands-free mode where Android allows it.
 */
class JarvisVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        if (Prefs.handsFree &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                WakeWordService.start(this)
            } catch (_: Exception) {
                // Android 14+ can refuse to start microphone services from the background. Opening the app once fixes it.
            }
        }
    }
}

class JarvisSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = JarvisSession(this)
}

class JarvisSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val i = Intent(context, MainActivity::class.java).apply {
            action = "com.jarvis.assistant.LISTEN"
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        try {
            startAssistantActivity(i)
        } catch (e: Exception) {
            try {
                context.startActivity(i)
            } catch (_: Exception) {
            }
        }
        hide()
    }
}

/**
 * Android wants every assistant to ship a speech recogniser. Jarvis does its listening itself, so this one simply
 * declines, and apps fall back to the normal system recogniser.
 */
class JarvisRecognitionService : android.speech.RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        try {
            listener?.error(android.speech.SpeechRecognizer.ERROR_CLIENT)
        } catch (_: Exception) {
        }
    }

    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}
