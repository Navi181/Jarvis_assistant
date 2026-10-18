package com.jarvis.assistant.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jarvis.assistant.core.A11y
import com.jarvis.assistant.core.Assistant
import com.jarvis.assistant.core.Prefs

@Composable
fun SetupScreen(canGoBack: Boolean, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val uri = LocalUriHandler.current

    // Re-evaluate statuses whenever we come back from a system settings page.
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    @Suppress("UNUSED_VARIABLE")
    val refresh = tick // reading it makes this screen re-check permission statuses on resume

    fun has(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    val perms = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.CALL_PHONE)
        add(Manifest.permission.READ_CALENDAR)
        add(Manifest.permission.WRITE_CALENDAR)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        add(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE)
        if (Build.VERSION.SDK_INT >= 29) add(Manifest.permission.ACCESS_MEDIA_LOCATION)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }

    var provider by remember { mutableStateOf(Prefs.provider) }
    var gem by remember { mutableStateOf(Prefs.geminiKey) }
    var groq by remember { mutableStateOf(Prefs.groqKey) }
    var claude by remember { mutableStateOf(Prefs.claudeKey) }
    var pico by remember { mutableStateOf(Prefs.picoKey) }
    var callMe by remember { mutableStateOf(Prefs.callMe) }
    var city by remember { mutableStateOf(Prefs.homeCity) }
    var conv by remember { mutableStateOf(Prefs.continuous) }
    val batteryOk = (ctx.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)

    val overlayOk = Settings.canDrawOverlays(ctx)
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { tick++ }
    val isDefault = runCatching {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = ctx.getSystemService(android.app.role.RoleManager::class.java)
            if (rm != null && rm.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT)) return@runCatching true
        }
        val a = Settings.Secure.getString(ctx.contentResolver, "assistant").orEmpty()
        val v = Settings.Secure.getString(ctx.contentResolver, "voice_interaction_service").orEmpty()
        a.contains(ctx.packageName) || v.contains(ctx.packageName)
    }.getOrDefault(false)

    if (canGoBack) BackHandler { onDone() }

    Box(
        Modifier
            .fillMaxSize()
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text("Set up Jarvis", color = Ink, fontFamily = Fonts.title, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Text(
                "A few quick things and you're done. Everything is free.",
                color = InkDim, fontSize = 14.sp
            )

            // 1 permissions
            Card("1 · Permissions", "So Jarvis can hear you, find contacts, place calls and find your photos.") {
                Status("Microphone", has(Manifest.permission.RECORD_AUDIO))
                Status("Contacts", has(Manifest.permission.READ_CONTACTS))
                Status("Phone calls", has(Manifest.permission.CALL_PHONE))
                Status("Calendar", has(Manifest.permission.READ_CALENDAR) && has(Manifest.permission.WRITE_CALENDAR))
                if (Build.VERSION.SDK_INT >= 33) Status("Notifications", has(Manifest.permission.POST_NOTIFICATIONS))
                Spacer(Modifier.height(4.dp))
                PrimaryButton("Grant permissions") { permLauncher.launch(perms.toTypedArray()) }
            }

            // 2 brain
            Card("2 · AI brain (free)", "Handles questions and natural phrasing. Calls, alarms and apps also work offline.") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = provider == "gemini",
                        onClick = { provider = "gemini"; Prefs.provider = "gemini" },
                        label = { Text("Gemini") }
                    )
                    FilterChip(
                        selected = provider == "groq",
                        onClick = { provider = "groq"; Prefs.provider = "groq" },
                        label = { Text("Groq") }
                    )
                    FilterChip(
                        selected = provider == "claude",
                        onClick = { provider = "claude"; Prefs.provider = "claude" },
                        label = { Text("Claude") }
                    )
                }
                when (provider) {
                    "gemini" -> {
                        KeyField("Gemini API key", gem) { gem = it; Prefs.geminiKey = it }
                        Link("Get a free key: aistudio.google.com/apikey") { uri.openUri("https://aistudio.google.com/apikey") }
                    }
                    "groq" -> {
                        KeyField("Groq API key", groq) { groq = it; Prefs.groqKey = it }
                        Link("Get a free key: console.groq.com/keys") { uri.openUri("https://console.groq.com/keys") }
                    }
                    else -> {
                        KeyField("Claude API key (paid)", claude) { claude = it; Prefs.claudeKey = it }
                        Link("Get a key: console.anthropic.com") { uri.openUri("https://console.anthropic.com/") }
                    }
                }
                Text(
                    "Tip: add more than one key. If one runs out of free quota, Jarvis switches to the next on its own.",
                    color = InkDim, fontSize = 13.sp, lineHeight = 18.sp
                )
                if (com.jarvis.assistant.core.Llm.lastProblem.isNotBlank()) {
                    Text("Last AI problem: " + com.jarvis.assistant.core.Llm.lastProblem, color = Warn, fontSize = 13.sp)
                }
                OutlinedTextField(
                    value = callMe,
                    onValueChange = { callMe = it; Prefs.callMe = it },
                    label = { Text("What should Jarvis call you?") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = fieldColors()
                )
            }

            // 3 hands-free
            Card("3 · Hands-free wake word", "Say “Jarvis” any time. It runs on your phone, free, with no account or key. Use Wi-Fi for the one-time download.") {
                var ready by remember { mutableStateOf(com.jarvis.assistant.core.WakeModel.ready(ctx)) }
                var pct by remember { mutableIntStateOf(-1) }
                Status("Wake-word voice model (about 40 MB)", ready)
                if (!ready) {
                    PrimaryButton(if (pct in 0..99) "Downloading… $pct%" else "Download wake-word model") {
                        if (pct !in 0..99) {
                            pct = 0
                            scope.launch {
                                val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    com.jarvis.assistant.core.WakeModel.download(ctx) { p -> pct = p }
                                }
                                pct = -1
                                ready = ok
                                android.widget.Toast.makeText(
                                    ctx,
                                    if (ok) "Wake-word model ready. Switch on Hands-free on the main screen."
                                    else "Download failed: " + com.jarvis.assistant.core.WakeModel.lastError,
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                }
                Text(
                    "Then switch on “Hands-free” on the main screen.",
                    color = InkDim, fontSize = 13.sp
                )
                KeyField("Picovoice AccessKey (optional, slightly more accurate)", pico) { pico = it; Prefs.picoKey = it }
                Link("Optional free key: console.picovoice.ai") { uri.openUri("https://console.picovoice.ai/") }
            }

            // 4 system
            Card("4 · Make Jarvis your phone's assistant", "Power-button long-press and headset button open Jarvis, like Google Assistant.") {
                Status("Default digital assistant", isDefault)
                PrimaryButton("Choose Jarvis as default assistant") {
                    var asked = false
                    if (Build.VERSION.SDK_INT >= 29) {
                        try {
                            val rm = ctx.getSystemService(android.app.role.RoleManager::class.java)
                            if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_ASSISTANT)) {
                                roleLauncher.launch(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_ASSISTANT))
                                asked = true
                            }
                        } catch (_: Exception) {
                        }
                    }
                    if (!asked) {
                        try { ctx.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) } catch (_: Exception) {
                            ctx.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
                        }
                    }
                }
                Text(
                    "A box will ask “Set Jarvis as default digital assistant?”. Tap Set as default. If you do it from Android's own settings instead: Settings, Apps, Choose default apps, Digital assistant app, Device assistance app, Jarvis, then tap OK on the warning.",
                    color = InkDim, fontSize = 13.sp
                )
                Spacer(Modifier.height(6.dp))
                Status("Display over other apps (shows a small orange orb when Jarvis is listening, and lets hands-free open apps)", overlayOk)
                PrimaryButton("Allow display over other apps") {
                    ctx.startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName))
                    )
                }
            }

            Card("5 · Extras & updates", "Home city for weather, conversation mode, notification reading and automatic updates. Version: ${com.jarvis.assistant.core.Updater.versionLabel(ctx)}.") {
                OutlinedTextField(
                    value = city, onValueChange = { city = it; Prefs.homeCity = it }, singleLine = true,
                    label = { Text("Your city (for weather and briefings)") }, modifier = Modifier.fillMaxWidth(), colors = fieldColors()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Keep listening after answers", color = Ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    androidx.compose.material3.Switch(checked = conv, onCheckedChange = { conv = it; Prefs.continuous = it })
                }
                Status("Read notifications aloud", com.jarvis.assistant.service.JarvisNotificationListener.connected)
                PrimaryButton("Allow notification access") { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                Status("Unrestricted battery (keeps hands-free alive)", batteryOk)
                PrimaryButton("Allow unrestricted battery") {
                    try { ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName))) }
                    catch (_: Exception) { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                }
                Status("Allowed to install updates", com.jarvis.assistant.core.Updater.canInstall(ctx))
                PrimaryButton("Allow installing updates") { com.jarvis.assistant.core.Updater.openInstallPermission(ctx) }
                PrimaryButton("Check for updates now") {
                    scope.launch {
                        val msg = com.jarvis.assistant.core.Updater.check(ctx, true)
                        android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
                    }
                }
                if (com.jarvis.assistant.core.CrashLog.last(ctx) != null) {
                    Text("Jarvis crashed last time. Sharing the report helps get it fixed.", color = Warn, fontSize = 13.sp)
                    PrimaryButton("Share crash report") { com.jarvis.assistant.core.CrashLog.share(ctx) }
                }
            }

            Card("6 · Voice", "Choose how Jarvis sounds. Move the slider left for a deeper voice, and tap Next voice until you find one you like.") {
                var pitch by remember { mutableFloatStateOf(Prefs.voicePitch) }
                var label by remember { mutableStateOf("") }
                Text("Deeper  ←  →  Higher", color = InkDim, fontSize = 13.sp)
                Slider(
                    value = pitch,
                    onValueChange = { pitch = it },
                    onValueChangeFinished = { Prefs.voicePitch = pitch; Assistant.applyVoiceSettings(); Assistant.previewVoice() },
                    valueRange = 0.4f..1.1f,
                    colors = SliderDefaults.colors(thumbColor = Cyan, activeTrackColor = Cyan)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton("Next voice") { label = Assistant.cycleVoice(); Assistant.previewVoice() }
                    PrimaryButton("Test") { Assistant.previewVoice() }
                }
                if (label.isNotBlank()) Text(label, color = InkDim, fontSize = 13.sp)
                var ai by remember { mutableStateOf(Prefs.aiVoice) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Jarvis voice: deep British man (online, uses your Gemini key)", color = Ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    androidx.compose.material3.Switch(checked = ai, onCheckedChange = { ai = it; Prefs.aiVoice = it; Assistant.previewVoice() })
                }
                var deep by remember { mutableStateOf(Prefs.deepVoice) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Deep male voice (works with any phone voice)", color = Ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    androidx.compose.material3.Switch(checked = deep, onCheckedChange = { deep = it; Prefs.deepVoice = it; Assistant.applyVoiceSettings(); Assistant.previewVoice() })
                }
                PrimaryButton("Get more voices (Speech Services by Google)") {
                    try {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.tts")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Exception) {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.tts")))
                    }
                }
            }

            Card("8 · Make it yours", "Personality, answer length, language and memory.") {
                var personality by remember { mutableStateOf(Prefs.personality) }
                var len by remember { mutableStateOf(Prefs.answerLen) }
                var lang by remember { mutableStateOf(Prefs.language) }
                var orb by remember { mutableStateOf(Prefs.listenOrb) }
                Text("Personality", color = InkDim, fontSize = 13.sp)
                Choice(listOf("witty" to "Witty", "formal" to "Formal", "friendly" to "Friendly", "sarcastic" to "Sarcastic"), personality) { personality = it; Prefs.personality = it }
                Text("Answer length", color = InkDim, fontSize = 13.sp)
                Choice(listOf("short" to "Short", "normal" to "Normal", "detailed" to "Detailed"), len) { len = it; Prefs.answerLen = it }
                Text("Language (listening and speaking)", color = InkDim, fontSize = 13.sp)
                Choice(
                    listOf("en-IN" to "English", "hi-IN" to "हिन्दी", "kn-IN" to "ಕನ್ನಡ", "ta-IN" to "தமிழ்", "te-IN" to "తెలుగు", "ml-IN" to "മലയാളം", "mr-IN" to "मराठी", "bn-IN" to "বাংলা"),
                    lang
                ) { lang = it; Prefs.language = it; Assistant.applyVoiceSettings() }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Small orange orb while listening in other apps", color = Ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    androidx.compose.material3.Switch(checked = orb, onCheckedChange = { orb = it; Prefs.listenOrb = it })
                }
                Text(
                    "Tell Jarvis “remember that …” and it keeps it. Say “what do you remember about me” to hear it, or “forget everything”.",
                    color = InkDim, fontSize = 13.sp
                )
            }

            Card("7 · WhatsApp auto-send & focus lock", "Optional. Lets Jarvis press Send in WhatsApp for you, and bring itself back if you leave during a focus session. It never reads or stores anything.") {
                Status("Accessibility access", A11y.enabled(ctx))
                PrimaryButton("Open accessibility settings") { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                Text(
                    "Tap Jarvis in the list, then switch it on. If it is greyed out: Settings, Apps, Jarvis, the three dots at the top, Allow restricted settings, then try again.",
                    color = InkDim, fontSize = 13.sp
                )
            }

            Button(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Bg)
            ) { Text(if (canGoBack) "Done" else "Start using Jarvis", fontWeight = FontWeight.SemiBold) }

            TextButton(onClick = onDone, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Skip for now", color = InkDim)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun Choice(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = selected == value, onClick = { onPick(value) },
                label = { Text(label, fontSize = 13.sp, maxLines = 1, softWrap = false) }
            )
        }
    }
}

@Composable
private fun Card(title: String, subtitle: String, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Panel,
        border = BorderStroke(1.dp, Outline)
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, color = Ink, fontFamily = Fonts.title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = InkDim, fontSize = 13.sp, lineHeight = 18.sp)
            content()
        }
    }
}

@Composable
private fun Status(label: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).background(if (ok) Good else Warn, CircleShape))
        Text(label, color = Ink, fontSize = 14.sp, modifier = Modifier.padding(start = 10.dp))
    }
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Cyan.copy(alpha = 0.16f), contentColor = Cyan)
    ) { Text(text, fontSize = 14.sp) }
}

@Composable
private fun Link(text: String, onClick: () -> Unit) {
    Text(
        text, color = Cyan, fontSize = 13.sp, textDecoration = TextDecoration.Underline,
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
private fun KeyField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
        colors = fieldColors()
    )
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Cyan,
    unfocusedBorderColor = Outline,
    focusedLabelColor = Cyan,
    unfocusedLabelColor = InkDim,
    focusedTextColor = Ink,
    unfocusedTextColor = Ink,
    cursorColor = Cyan
)
