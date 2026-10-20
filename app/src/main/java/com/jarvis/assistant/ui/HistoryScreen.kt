package com.jarvis.assistant.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.core.History
import java.text.DateFormat
import java.util.Date

/** Everything said so far: read it, copy a line, or share the whole conversation. */
@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf(History.all()) }
    val state = rememberLazyListState()
    LaunchedEffect(list.size) { if (list.isNotEmpty()) state.scrollToItem(list.size - 1) }

    fun copy(text: String) {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Jarvis", text))
        Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
    }

    Column(Modifier.fillMaxSize().background(Bg).windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = InkDim) }
            Text("History", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = { copy(History.asText(list)) }) { Icon(Icons.Rounded.ContentCopy, "Copy all", tint = InkDim) }
            IconButton(onClick = {
                val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, History.asText(list))
                ctx.startActivity(Intent.createChooser(i, "Share conversation").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Icon(Icons.Rounded.Share, "Share", tint = InkDim) }
            IconButton(onClick = { History.clear(); list = emptyList() }) { Icon(Icons.Rounded.DeleteOutline, "Clear", tint = Warn) }
        }
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nothing here yet. Your conversations will show up here.", color = InkDim, fontSize = 14.sp)
            }
        } else {
            Text("Tap a message to copy it.", color = InkDim, fontSize = 12.sp, modifier = Modifier.padding(start = 12.dp, bottom = 6.dp))
            LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(list) { e ->
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (e.fromUser) Alignment.End else Alignment.Start) {
                        Column(
                            Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(16.dp))
                                .background(if (e.fromUser) Cyan.copy(alpha = 0.16f) else Ink.copy(alpha = 0.07f))
                                .clickable { copy(e.text) }.padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Text(e.text, color = Ink, fontSize = 15.sp)
                            Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(e.time)), color = InkDim, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }
    }
}
