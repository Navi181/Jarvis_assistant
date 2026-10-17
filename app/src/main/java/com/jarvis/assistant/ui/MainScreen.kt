package com.jarvis.assistant.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.jarvis.assistant.core.Assistant
import com.jarvis.assistant.core.Msg
import com.jarvis.assistant.core.Phase
import com.jarvis.assistant.core.Prefs
import com.jarvis.assistant.service.WakeWordService

private val suggestions = listOf(
    "Set a timer for 5 minutes",
    "What's my battery level?",
    "Turn on the flashlight",
    "Open YouTube",
    "Tell me a fun fact",
    "Play lo-fi music"
)

@Composable
fun MainScreen(onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val phase by Assistant.phase.collectAsState()
    val level by Assistant.level.collectAsState()
    val partial by Assistant.partial.collectAsState()
    val msgs by Assistant.messages.collectAsState()
    var handsFree by remember { mutableStateOf(Prefs.handsFree) }
    var typed by remember { mutableStateOf("") }

    fun sendTyped() {
        val t = typed.trim()
        if (t.isNotEmpty()) {
            Assistant.submit(t)
            typed = ""
        }
    }

    fun hasMic() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) Assistant.listen()
        else Toast.makeText(ctx, "Jarvis needs the microphone to hear you.", Toast.LENGTH_LONG).show()
    }

    // Bring hands-free back after the app process was killed.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (Prefs.handsFree && !WakeWordService.running && hasMic()) WakeWordService.start(ctx)
    }

    fun tapMic() {
        if (phase == Phase.IDLE) {
            if (hasMic()) Assistant.listen() else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else Assistant.cancel()
    }

    fun setHandsFree(on: Boolean) {
        if (on) {
            if (!hasMic()) {
                micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                return
            }
            Prefs.handsFree = true
            WakeWordService.start(ctx)
            handsFree = true
        } else {
            Prefs.handsFree = false
            WakeWordService.stop(ctx)
            handsFree = false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // top bar
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "J.A.R.V.I.S",
                    color = Ink, fontFamily = Fonts.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onSettings) {
                    Icon(Icons.Rounded.Settings, contentDescription = "Setup", tint = InkDim)
                }
            }

            // orb
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val s = if (maxWidth < maxHeight) maxWidth else maxHeight
                JarvisOrb(phase, level, Modifier.size(s))
            }

            // status line
            val status = when (phase) {
                Phase.IDLE -> if (handsFree) "Say “Jarvis” or tap the mic" else "Tap the mic to talk"
                Phase.LISTENING -> partial.ifBlank { "Listening…" }
                Phase.THINKING -> "Thinking…"
                Phase.SPEAKING -> "Speaking…"
            }
            AnimatedContent(
                targetState = status,
                transitionSpec = {
                    (fadeIn(tween(250)) + slideInVertically(tween(250)) { it / 3 }) togetherWith
                        (fadeOut(tween(150)) + slideOutVertically(tween(150)) { -it / 3 })
                },
                label = "status"
            ) { text ->
                Text(
                    text, color = if (phase == Phase.LISTENING && partial.isNotBlank()) Ink else InkDim,
                    fontSize = 16.sp, textAlign = TextAlign.Center, maxLines = 2,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }

            // conversation
            Column(
                Modifier.fillMaxWidth().heightIn(min = 96.dp).padding(top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom)
            ) {
                msgs.takeLast(2).forEach { m -> key(m.id) { Bubble(m) } }
            }

            // suggestions
            AnimatedVisibility(
                visible = phase == Phase.IDLE && msgs.isEmpty(),
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut()
            ) {
                LazyRow(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(suggestions) { s ->
                        AssistChip(onClick = { Assistant.submit(s) }, label = { Text(s, color = Ink, fontSize = 13.sp) })
                    }
                }
            }

            // keyboard
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    placeholder = { Text("Type a command…", color = InkDim) },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { sendTyped() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Cyan,
                        unfocusedBorderColor = Outline,
                        focusedTextColor = Ink,
                        unfocusedTextColor = Ink,
                        cursorColor = Cyan
                    )
                )
                IconButton(onClick = { sendTyped() }, enabled = typed.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send", tint = if (typed.isNotBlank()) Cyan else InkDim)
                }
            }

            // mic
            Box(Modifier.padding(top = 12.dp), contentAlignment = Alignment.Center) {
                MicButton(phase, level, ::tapMic)
            }

            // hands-free toggle
            Row(
                Modifier
                    .padding(top = 10.dp, bottom = 14.dp)
                    .clickable { setHandsFree(!handsFree) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Hands-free “Jarvis”", color = if (handsFree) Cyan else InkDim, fontSize = 14.sp)
                Switch(
                    checked = handsFree,
                    onCheckedChange = { setHandsFree(it) },
                    modifier = Modifier.padding(start = 10.dp).scale(0.85f),
                    colors = SwitchDefaults.colors(checkedTrackColor = Cyan, checkedThumbColor = Bg)
                )
            }
        }
    }
}

@Composable
private fun Bubble(m: Msg) {
    val state = remember(m.id) { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(
        visibleState = state,
        enter = fadeIn(tween(350)) + slideInVertically(tween(350)) { it / 2 } + scaleIn(tween(350), initialScale = 0.92f)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = if (m.fromUser) Arrangement.End else Arrangement.Start
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = if (m.fromUser) Cyan.copy(alpha = 0.14f) else Panel,
                border = BorderStroke(1.dp, if (m.fromUser) Cyan.copy(alpha = 0.35f) else Outline),
                modifier = Modifier.widthIn(max = 310.dp)
            ) {
                Text(
                    m.text, color = Ink, fontSize = 15.sp, lineHeight = 21.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
        }
    }
}

@Composable
private fun MicButton(phase: Phase, level: Float, onClick: () -> Unit) {
    val active = phase != Phase.IDLE
    val pulse = rememberInfiniteTransition(label = "mic")
    val ring by pulse.animateFloat(
        1f, 1.45f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "ring"
    )
    val press by animateFloatAsState(
        if (phase == Phase.LISTENING) 1f + level * 0.18f else 1f, tween(120), label = "press"
    )
    Box(contentAlignment = Alignment.Center) {
        if (active) {
            Box(
                Modifier
                    .size(88.dp)
                    .scale(ring)
                    .alpha(0.18f)
                    .background(Cyan, CircleShape)
            )
        }
        Box(
            Modifier
                .size(80.dp)
                .scale(press)
                .background(
                    Brush.linearGradient(if (active) listOf(Violet, Blue) else listOf(Cyan, Blue)),
                    CircleShape
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.animation.Crossfade(active, label = "micIcon") { on ->
                Icon(
                    if (on) Icons.Rounded.Stop else Icons.Rounded.Mic,
                    contentDescription = if (on) "Stop" else "Talk to Jarvis",
                    tint = Color.White, modifier = Modifier.size(34.dp)
                )
            }
        }
    }
}
