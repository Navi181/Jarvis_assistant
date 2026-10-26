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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
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
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
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
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PhotoCamera
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
import com.jarvis.assistant.core.MediaAsk
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
fun MainScreen(onSettings: () -> Unit, onHistory: () -> Unit = {}) {
    val ctx = LocalContext.current
    val phase by Assistant.phase.collectAsState()
    val level by Assistant.level.collectAsState()
    val partial by Assistant.partial.collectAsState()
    val msgs by Assistant.messages.collectAsState()
    val media by Assistant.media.collectAsState()
    var handsFree by remember { mutableStateOf(Prefs.handsFree) }
    var typed by remember { mutableStateOf("") }
    // While the keyboard is up, the orb shrinks and everything else steps aside so you can see what you type.
    val imeOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    // a long answer gets the room: the orb shrinks and the answer scrolls
    val longReply = msgs.lastOrNull()?.let { !it.fromUser && it.text.length > 150 } == true
    val compact = imeOpen || longReply
    val replyScroll = rememberScrollState()
    androidx.compose.runtime.LaunchedEffect(msgs.lastOrNull()?.id) { replyScroll.scrollTo(0) }
    var focused by remember { mutableStateOf(false) }

    fun sendTyped() {
        val t = typed.trim()
        if (media != null) {
            Assistant.submitMedia(t)
            typed = ""
        } else if (t.isNotEmpty()) {
            Assistant.submit(t)
            typed = ""
        }
    }

    // take a photo inside Jarvis and ask about it ("look at this")
    var shotUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var lookAuto by remember { mutableStateOf(false) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = shotUri
        if (ok && u != null) {
            try {
                Assistant.attach(MediaAsk.describe(ctx, u))
                if (lookAuto) Assistant.submitMedia("")
            } catch (e: Exception) {
                Toast.makeText(ctx, "I couldn't use that photo.", Toast.LENGTH_LONG).show()
            }
        }
        lookAuto = false
    }
    fun shoot(auto: Boolean) {
        try {
            val dir = java.io.File(ctx.cacheDir, "shots").apply { mkdirs() }
            val f = java.io.File(dir, "shot_" + System.currentTimeMillis() + ".jpg")
            val u = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
            shotUri = u
            lookAuto = auto
            takePhoto.launch(u)
        } catch (e: Exception) {
            Toast.makeText(ctx, "I couldn't open the camera.", Toast.LENGTH_LONG).show()
        }
    }
    val wantLook by Assistant.pendingLook.collectAsState()
    androidx.compose.runtime.LaunchedEffect(wantLook) {
        if (wantLook) {
            Assistant.pendingLook.value = false
            shoot(true)
        }
    }

    // attach a video, photo, audio clip or PDF from the phone
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                Assistant.attach(MediaAsk.describe(ctx, uri))
            } catch (e: Exception) {
                Toast.makeText(ctx, "I couldn't open that file.", Toast.LENGTH_LONG).show()
            }
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
                IconButton(onClick = onHistory) {
                    Icon(Icons.Rounded.History, contentDescription = "History", tint = InkDim)
                }
                IconButton(onClick = onSettings) {
                    Icon(Icons.Rounded.Settings, contentDescription = "Setup", tint = InkDim)
                }
            }

            // orb
            if (compact) {
                JarvisOrb(phase, level, Modifier.padding(top = 4.dp).size(84.dp))
            } else {
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val s = if (maxWidth < maxHeight) maxWidth else maxHeight
                    JarvisOrb(phase, level, Modifier.size(s))
                }
            }

            // status line
            val status = when (phase) {
                Phase.IDLE -> if (imeOpen) "Type a command" else if (handsFree) "Say “Jarvis” or tap the mic" else "Tap the mic to talk"
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
                (if (compact) Modifier.weight(1f).verticalScroll(replyScroll) else Modifier.heightIn(min = 96.dp)).fillMaxWidth().padding(top = 6.dp),
                verticalArrangement = if (compact) Arrangement.spacedBy(8.dp, Alignment.Top) else Arrangement.spacedBy(8.dp, Alignment.Bottom)
            ) {
                msgs.takeLast(if (compact) 3 else 2).forEach { m -> key(m.id) { Box(Modifier.appear()) { Bubble(m) } } }
            }

            // suggestions
            AnimatedVisibility(
                visible = phase == Phase.IDLE && msgs.isEmpty() && !imeOpen,
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

            // the attached file, with a cross to let go of it
            media?.let { a ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Cyan.copy(alpha = 0.10f))
                        .border(1.dp, Cyan.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                        .padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.AttachFile, contentDescription = null, tint = Cyan, modifier = Modifier.size(18.dp))
                    Text(
                        "${a.name}  ·  ask me about this ${a.kind}",
                        color = Ink, fontSize = 13.sp, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 8.dp)
                    )
                    IconButton(onClick = { Assistant.attach(null) }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Remove attachment", tint = InkDim, modifier = Modifier.size(18.dp))
                    }
                }
            }

            // keyboard: one soft rounded box, the text inside it, a round send button on the right
            val boxShape = RoundedCornerShape(26.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = if (imeOpen) 10.dp else 0.dp)
                    .clip(boxShape)
                    .background(Panel)
                    .border(1.dp, if (focused) Cyan.copy(alpha = 0.55f) else Outline, boxShape)
                    .padding(start = 6.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable { pickFile.launch(arrayOf("video/*", "image/*", "audio/*", "application/pdf")) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.AttachFile, contentDescription = "Attach a video, photo or file", tint = if (media != null) Cyan else InkDim)
                }
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable { shoot(false) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.PhotoCamera, contentDescription = "Take a photo to ask about", tint = InkDim)
                }
                BasicTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    textStyle = TextStyle(color = Ink, fontSize = 17.sp, lineHeight = 24.sp, letterSpacing = 0.sp),
                    cursorBrush = SolidColor(Cyan),
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { sendTyped() }),
                    modifier = Modifier.weight(1f).heightIn(min = 40.dp).padding(start = 4.dp, top = 8.dp, bottom = 8.dp).onFocusChanged { focused = it.isFocused },
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (typed.isEmpty()) Text(if (media != null) "Ask about this ${media?.kind}…" else "Message Jarvis…", color = InkDim, fontSize = 17.sp)
                            inner()
                        }
                    }
                )
                Box(
                    Modifier
                        .padding(start = 6.dp)
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (typed.isNotBlank() || media != null) Cyan else Outline)
                        .clickable(enabled = typed.isNotBlank() || media != null) { sendTyped() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.ArrowUpward, contentDescription = "Send", tint = if (typed.isNotBlank() || media != null) Bg else InkDim)
                }
            }

            // mic
            if (!imeOpen) Box(Modifier.padding(top = 12.dp), contentAlignment = Alignment.Center) {
                MicButton(phase, level, ::tapMic)
            }

            // hands-free toggle
            if (!imeOpen) Row(
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
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = if (m.fromUser) Alignment.End else Alignment.Start
        ) {
            Text(
                if (m.fromUser) Prefs.callMe.replaceFirstChar { it.uppercase() } else "Jarvis",
                color = InkDim, fontFamily = Fonts.mono, fontSize = 10.sp, letterSpacing = 1.sp,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
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
