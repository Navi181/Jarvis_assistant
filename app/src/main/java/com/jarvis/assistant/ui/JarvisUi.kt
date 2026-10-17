package com.jarvis.assistant.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.core.Prefs
import com.jarvis.assistant.service.WakeWordService

enum class Tab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Rounded.Home),
    Study("Study", Icons.Rounded.School),
    Skills("Skills", Icons.Rounded.GridView),
    Setup("Setup", Icons.Rounded.Settings)
}

@Composable
fun JarvisUi() {
    val ctx = LocalContext.current
    JarvisTheme {
        var intro by remember { mutableStateOf(true) }
        var signedIn by remember { mutableStateOf(Prefs.signedIn) }
        var tab by remember { mutableStateOf(if (Prefs.onboarded) Tab.Home else Tab.Setup) }

        if (intro) {
            IntroScreen { intro = false }
        } else if (!signedIn) {
            SignInScreen { signedIn = true }
        } else {
            Column(Modifier.fillMaxSize().background(Bg)) {
                Box(Modifier.weight(1f)) {
                    AnimatedContent(
                        targetState = tab,
                        transitionSpec = {
                            val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                            (slideInHorizontally(tween(320)) { it / 4 * dir } + fadeIn(tween(320))) togetherWith
                                (slideOutHorizontally(tween(220)) { -it / 4 * dir } + fadeOut(tween(200)))
                        },
                        label = "tabs"
                    ) { t ->
                        when (t) {
                            Tab.Home -> Box(Modifier.fillMaxSize()) {
                                Backdrop()
                                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
                                    MainScreen(onSettings = { tab = Tab.Setup })
                                }
                            }
                            Tab.Study -> StudyScreen()
                            Tab.Skills -> SkillsScreen(onRun = { tab = Tab.Home })
                            Tab.Setup -> Box(Modifier.fillMaxSize()) {
                                Backdrop()
                                SetupScreen(canGoBack = Prefs.onboarded) {
                                    Prefs.onboarded = true
                                    // After setup, hands-free turns itself on if the wake-word key is present.
                                    if ((Prefs.picoKey.isNotBlank() || com.jarvis.assistant.core.WakeModel.ready(ctx)) && !Prefs.handsFree) Prefs.handsFree = true
                                    if (Prefs.handsFree) WakeWordService.start(ctx)
                                    tab = Tab.Home
                                }
                            }
                        }
                    }
                }
                NavBar(tab) { tab = it }
            }
        }
    }
}

@Composable
private fun NavBar(selected: Tab, onSelect: (Tab) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Bg)))
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Tab.values().forEach { t ->
            val sel = t == selected
            val tint by animateColorAsState(if (sel) Cyan else InkDim, tween(250), label = "tint")
            val bg by animateColorAsState(if (sel) Cyan.copy(alpha = 0.16f) else Color.Transparent, tween(250), label = "bg")
            Row(
                Modifier.clip(RoundedCornerShape(22.dp)).background(bg).clickable { onSelect(t) }
                    .padding(horizontal = 14.dp, vertical = 9.dp).animateContentSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(t.icon, t.label, tint = tint)
                AnimatedVisibility(sel) { Text(t.label, color = tint, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp)) }
            }
        }
    }
}
