package com.jarvis.assistant.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.core.Assistant

private val groups = listOf(
    "Calls & messages" to listOf("Call mom", "Text Rahul saying I'll be late", "WhatsApp Priya saying hi"),
    "Time" to listOf("Set an alarm for 6:30 am", "Set a timer for 10 minutes", "Remind me to drink water in half an hour"),
    "Direct answers" to listOf("Who won yesterday's cricket match?", "What's the latest news?", "What's the weather in Mumbai?", "What is 15 percent of 2400?", "Convert 10 miles to km", "Convert 100 dollars to rupees"),
    "Photos" to listOf("Open a photo of the beach", "Show me photos from yesterday", "Open a photo with 3 people", "Show photos from Goa last week"),
    "Phone control" to listOf("Turn on the flashlight", "Volume up", "Open YouTube", "Play lo-fi on YouTube", "Pause", "Next song", "Navigate to the airport", "Open the camera"),
    "Memory" to listOf("Take a note buy milk", "Read my notes", "Read my notifications", "Good morning"),
    "Study" to listOf("Explain photosynthesis", "Quiz me on World War 2", "Flashcards for the periodic table"),
    "Jarvis itself" to listOf("Update yourself")
)

/** Everything Jarvis can do. Tap any phrase to try it. */
@Composable
fun SkillsScreen(onRun: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Backdrop()
        LazyColumn(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text("Skills", color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Light, letterSpacing = 2.sp, modifier = Modifier.padding(top = 12.dp))
                Text("Tap any phrase to try it. Or just say it.", color = InkDim, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
            }
            groups.forEach { (title, phrases) ->
                item { Text(title, color = Cyan, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 10.dp)) }
                items(phrases) { p ->
                    Row(
                        Modifier.fillMaxWidth().glass().clickable { onRun(); Assistant.submit(p) }.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("“$p”", color = Ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Icon(Icons.Rounded.PlayArrow, null, tint = Cyan)
                    }
                }
            }
            item { Box(Modifier.padding(bottom = 16.dp)) }
        }
    }
}
