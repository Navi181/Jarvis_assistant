package com.jarvis.assistant.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.core.Assistant
import com.jarvis.assistant.core.Brain
import com.jarvis.assistant.core.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Study space: AI tutor (explain / quiz / flashcards / summary), focus timer, homework list. */
@Composable
fun StudyScreen() {
    val scope = rememberCoroutineScope()
    var level by remember { mutableStateOf(Prefs.studyLevel) }
    var topic by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val tasks = remember { mutableStateListOf<Pair<Boolean, String>>().apply { addAll(Prefs.tasks()) } }
    var newTask by remember { mutableStateOf("") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var minutes by remember { mutableIntStateOf(Prefs.focusMinutes) }
    var end by remember { mutableLongStateOf(Prefs.focusEnd) }
    val running = end > now

    LaunchedEffect(end) {
        while (end > System.currentTimeMillis()) {
            now = System.currentTimeMillis()
            delay(500)
        }
        now = System.currentTimeMillis()
        if (end != 0L) {
            val stale = now - end > 60_000
            Prefs.focusEnd = 0L
            end = 0L
            if (!stale) Assistant.say("Focus session complete. Well done. Take a short break.")
        }
    }

    fun ask(kind: String) {
        if (topic.isBlank() || busy) return
        busy = true
        output = ""
        scope.launch {
            output = withContext(Dispatchers.IO) { Brain.tutor(kind, topic, false) }
            busy = false
        }
    }

    fun saveTasks() = Prefs.saveTasks(tasks.toList())

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Cyan, unfocusedBorderColor = Outline, focusedLabelColor = Cyan,
        unfocusedLabelColor = InkDim, focusedTextColor = Ink, unfocusedTextColor = Ink, cursorColor = Cyan
    )

    Box(Modifier.fillMaxSize()) {
        Backdrop()
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Study", color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Light, letterSpacing = 2.sp)
            Text("Say “quiz me on photosynthesis” any time, or use the tools below.", color = InkDim, fontSize = 14.sp)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("middle school", "high school", "college").forEach { l ->
                    FilterChip(selected = level == l, onClick = { level = l; Prefs.studyLevel = l }, label = { Text(l) })
                }
            }

            // ---- focus timer
            Column(Modifier.fillMaxWidth().glass().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Focus session", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                val total = minutes * 60_000L
                val remaining = if (running) (end - now).coerceAtLeast(0L) else total
                val progress = if (total > 0) remaining.toFloat() / total else 0f
                Box(Modifier.size(170.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) {
                        val sw = 10.dp.toPx()
                        val d = size.minDimension - sw
                        val tl = Offset(sw / 2, sw / 2)
                        drawArc(Outline, 0f, 360f, false, tl, Size(d, d), style = Stroke(sw))
                        drawArc(
                            Brush.sweepGradient(listOf(Cyan, Blue, Violet, Cyan)), -90f, 360f * progress, false, tl, Size(d, d),
                            style = Stroke(sw, cap = StrokeCap.Round)
                        )
                    }
                    val sec = (remaining / 1000).toInt()
                    Text(String.format("%02d:%02d", sec / 60, sec % 60), color = Ink, fontSize = 34.sp, fontWeight = FontWeight.Light)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 25, 45, 60).forEach { m ->
                        FilterChip(selected = minutes == m, enabled = !running, onClick = { minutes = m; Prefs.focusMinutes = m }, label = { Text("$m min") })
                    }
                }
                Button(
                    onClick = {
                        if (running) { Prefs.focusEnd = 0L; end = 0L }
                        else { val e = System.currentTimeMillis() + minutes * 60_000L; Prefs.focusEnd = e; now = System.currentTimeMillis(); end = e }
                    },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Bg)
                ) { Text(if (running) "Stop" else "Start focusing") }
            }

            // ---- tutor
            Column(Modifier.fillMaxWidth().glass().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("AI tutor", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                OutlinedTextField(
                    value = topic, onValueChange = { topic = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Topic, e.g. photosynthesis") }, colors = fieldColors
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("explain" to "Explain", "quiz" to "Quiz me", "flashcards" to "Cards", "summary" to "Summary").forEach { (k, label) ->
                        FilterChip(selected = false, onClick = { ask(k) }, label = { Text(label, fontSize = 13.sp) })
                    }
                }
                Crossfade(if (busy) "busy" else output, label = "tutorOut") { o ->
                    when {
                        o == "busy" -> Text("Thinking…", color = Cyan, fontSize = 15.sp)
                        o.isNotBlank() -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(o, color = Ink, fontSize = 15.sp, lineHeight = 22.sp)
                            TextButton(onClick = { Assistant.say(o.take(1500)) }) { Text("Read aloud", color = Cyan) }
                        }
                        else -> Spacer(Modifier.height(1.dp))
                    }
                }
            }

            // ---- homework list
            Column(Modifier.fillMaxWidth().glass().padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Homework & goals", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                tasks.forEachIndexed { i, t ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = t.first,
                            onCheckedChange = { tasks[i] = it to t.second; saveTasks() },
                            colors = CheckboxDefaults.colors(checkedColor = Cyan, uncheckedColor = InkDim, checkmarkColor = Bg)
                        )
                        Text(
                            t.second, color = if (t.first) InkDim else Ink, fontSize = 15.sp,
                            textDecoration = if (t.first) TextDecoration.LineThrough else null,
                            modifier = Modifier.weight(1f).clickable { tasks[i] = !t.first to t.second; saveTasks() }
                        )
                        IconButton(onClick = { tasks.removeAt(i); saveTasks() }) { Icon(Icons.Rounded.Close, "Delete", tint = InkDim) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newTask, onValueChange = { newTask = it }, singleLine = true, modifier = Modifier.weight(1f),
                        label = { Text("Add a task") }, colors = fieldColors
                    )
                    IconButton(onClick = {
                        if (newTask.isNotBlank()) { tasks.add(false to newTask.trim()); newTask = ""; saveTasks() }
                    }) { Icon(Icons.Rounded.Add, "Add", tint = Cyan) }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
