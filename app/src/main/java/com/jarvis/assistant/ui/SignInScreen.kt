package com.jarvis.assistant.ui

import android.accounts.AccountManager
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.core.Phase
import com.jarvis.assistant.core.Prefs

/**
 * First-run welcome. Uses the Google accounts already on the phone (no passwords handled by the app),
 * or opens Android's own Google sign-up. After this, Jarvis is fully voice driven.
 */
@Composable
fun SignInScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(Prefs.userName) }
    var email by remember { mutableStateOf(Prefs.email) }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }

    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)?.let { acc ->
            email = acc
            if (name.isBlank()) name = acc.substringBefore('@').replace('.', ' ').replace('_', ' ').split(' ')
                .joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
            // picked a Google account: that's the sign-in, go straight in
            Prefs.userName = name
            Prefs.email = acc
            Prefs.callMe = name.trim().substringBefore(' ').ifBlank { "sir" }
            Prefs.signedIn = true
            onDone()
        }
    }

    fun finish() {
        Prefs.userName = name
        Prefs.email = email
        Prefs.callMe = name.trim().substringBefore(' ').ifBlank { "sir" }
        Prefs.signedIn = true
        onDone()
    }

    Box(Modifier.fillMaxSize()) {
        Backdrop()
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            AnimatedVisibility(shown, enter = fadeIn(tween(900)) + scaleIn(tween(900), initialScale = 0.75f)) {
                JarvisOrb(Phase.IDLE, 0f, Modifier.size(210.dp))
            }
            AnimatedVisibility(shown, enter = fadeIn(tween(900, delayMillis = 350)) + slideInVertically(tween(900, delayMillis = 350)) { it / 4 }) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Meet Jarvis", color = Ink, fontFamily = Fonts.title, fontSize = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp)
                    Text(
                        "Sign in once. After that, just talk. Everything is hands-free.",
                        color = InkDim, fontSize = 15.sp, textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = name, onValueChange = { name = it }, singleLine = true,
                        label = { Text("Your name") }, modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Cyan, unfocusedBorderColor = Outline, focusedLabelColor = Cyan,
                            unfocusedLabelColor = InkDim, focusedTextColor = Ink, unfocusedTextColor = Ink, cursorColor = Cyan
                        )
                    )
                    if (email.isNotBlank()) Text("Signed in as $email", color = Good, fontSize = 14.sp)
                    Button(
                        onClick = {
                            try {
                                chooser.launch(AccountManager.newChooseAccountIntent(null, null, arrayOf("com.google"), null, null, null, null))
                            } catch (e: Exception) {
                                Toast.makeText(ctx, "Couldn't open the account picker.", Toast.LENGTH_LONG).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Bg)
                    ) { Text("Sign in with Google", fontWeight = FontWeight.SemiBold) }
                    OutlinedButton(
                        onClick = {
                            try {
                                ctx.startActivity(Intent(Settings.ACTION_ADD_ACCOUNT).putExtra(Settings.EXTRA_ACCOUNT_TYPES, arrayOf("com.google")))
                                Toast.makeText(ctx, "When you're done, come back and tap Sign in with Google.", Toast.LENGTH_LONG).show()
                            } catch (e: Exception) {
                                Toast.makeText(ctx, "Couldn't open Google sign-up.", Toast.LENGTH_LONG).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)
                    ) { Text("Create a new Google account", color = InkDim) }
                    if (email.isBlank()) {
                        Text(
                            "No Google account on this phone? Create one above, then come back.",
                            color = InkDim, fontSize = 12.sp, textAlign = TextAlign.Center
                        )
                    } else {
                        OutlinedButton(
                            onClick = { finish() }, enabled = name.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)
                        ) { Text("Continue", color = Cyan) }
                    }
                }
            }
        }
    }
}
