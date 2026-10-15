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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
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
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        add(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE)
        if (Build.VERSION.SDK_INT >= 29) add(Manifest.permission.ACCESS_MEDIA_LOCATION)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }

    var provider by remember { mutableStateOf(Prefs.provider) }
    var gem by remember { mutableStateOf(Prefs.geminiKey) }
    var groq by remember { mutableStateOf(Prefs.groqKey) }
    var pico by remember { mutableStateOf(Prefs.picoKey) }
    var callMe by remember { mutableStateOf(Prefs.callMe) }
    var city by remember { mutableStateOf(Prefs.homeCity) }
    var conv by remember { mutableStateOf(Prefs.continuous) }
    val batteryOk = (ctx.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)

    val overlayOk = Settings.canDrawOverlays(ctx)
    val isDefault = runCatching {
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
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Set up Jarvis", color = Ink, fontSize = 26.sp, fontWeight = FontWeight.Light)
            Text(
                "Five quick things and you're done. Everything is free.",
                color = InkDim, fontSize = 14.sp
            )

            // 1 permissions
            Card("1 · Permissions", "So Jarvis can hear you, find contacts, place calls and find your photos.") {
                Status("Microphone", has(Manifest.permission.RECORD_AUDIO))
                Status("Contacts", has(Manifest.permission.READ_CONTACTS))
                Status("Phone calls", has(Manifest.permission.CALL_PHONE))
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
                }
                if (provider == "gemini") {
                    KeyField("Gemini API key", gem) { gem = it; Prefs.geminiKey = it }
                    Link("Get a free key: aistudio.google.com/apikey") { uri.openUri("https://aistudio.google.com/apikey") }
                } else {
                    KeyField("Groq API key", groq) { groq = it; Prefs.groqKey = it }
                    Link("Get a free key: console.groq.com/keys") { uri.openUri("https://console.groq.com/keys") }
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
            Card("3 · Hands-free wake word", "Say “Jarvis” any time. Detection runs on your phone; nothing is uploaded while waiting.") {
                KeyField("Picovoice AccessKey", pico) { pico = it; Prefs.picoKey = it }
                Link("Get a free key: console.picovoice.ai") { uri.openUri("https://console.picovoice.ai/") }
                Text(
                    "Then switch on “Hands-free” on the main screen.",
                    color = InkDim, fontSize = 13.sp
                )
            }

            // 4 system
            Card("4 · Make Jarvis your phone's assistant", "Power-button long-press and headset button open Jarvis, like Google Assistant.") {
                Status("Default digital assistant", isDefault)
                PrimaryButton("Choose Jarvis as default assistant") {
                    val i = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
                    try { ctx.startActivity(i) } catch (_: Exception) {
                        ctx.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
                    }
                }
                Spacer(Modifier.height(6.dp))
                Status("Display over other apps (lets hands-free open apps & calls)", overlayOk)
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
private fun Card(title: String, subtitle: String, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Panel,
        border = BorderStroke(1.dp, Outline)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Medium)
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
