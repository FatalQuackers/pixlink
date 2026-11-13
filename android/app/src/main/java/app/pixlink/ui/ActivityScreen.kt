package app.pixlink.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pixlink.activity.ActivityLog
import app.pixlink.activity.ActivityLog.Kind

/** Activity: everything sent to or received from the PC lately. Tap to open a file again or copy text. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    remember { ActivityLog.load(ctx) }
    val entries by ActivityLog.entries.collectAsState()

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Activity") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
            actions = {
                if (entries.isNotEmpty()) IconButton(onClick = { ActivityLog.clear(ctx) }) { Icon(Icons.Outlined.DeleteSweep, "Clear") }
            },
        )
    }) { pad ->
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.History, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
                    Text("Nothing yet", style = MaterialTheme.typography.titleMedium)
                    Text("Files, photos and clipboard you send or receive show up here.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 40.dp))
                }
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(entries, key = { it.time.toString() + it.title }) { e ->
                val (icon, verb) = when (e.kind) {
                    Kind.FileSent -> Icons.Outlined.Upload to "Sent to PC"
                    Kind.FileReceived -> Icons.Outlined.Download to "From PC"
                    Kind.ClipboardSent -> Icons.Outlined.ContentPaste to "Clipboard sent"
                    Kind.ClipboardReceived -> Icons.Outlined.ContentPaste to "Clipboard from PC"
                    Kind.TextShared -> Icons.Outlined.Share to "Shared to PC"
                }
                val isFile = e.kind == Kind.FileSent || e.kind == Kind.FileReceived
                ListItem(
                    leadingContent = {
                        Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                            Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    },
                    headlineContent = { Text(if (isFile) e.title else (e.detail ?: e.title), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text("$verb · " + DateUtils.getRelativeTimeSpanString(e.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS))
                    },
                    modifier = Modifier.fillMaxWidth().animateContentSize().clickable {
                        if (isFile) {
                            val opened = runCatching {
                                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(e.detail))
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                            }.isSuccess
                            if (!opened) Toast.makeText(ctx, "That file isn't available anymore.", Toast.LENGTH_SHORT).show()
                        } else {
                            ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Tethr Link", e.detail ?: ""))
                            Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            }
        }
    }
}
