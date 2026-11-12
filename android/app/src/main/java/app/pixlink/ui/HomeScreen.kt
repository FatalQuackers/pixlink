package app.pixlink.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.pixlink.link.LinkHub
import app.pixlink.link.LinkService
import app.pixlink.link.LinkState
import app.pixlink.notifications.NotificationBridge
import app.pixlink.pairing.PairedStore
import app.pixlink.power.PowerMode
import app.pixlink.update.AppUpdater
import app.pixlink.update.UpdateChecker
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onPair: () -> Unit,
    onPowerMode: () -> Unit,
    onSettings: () -> Unit,
    onGrantPermissions: () -> Unit,
) {
    val ctx = LocalContext.current
    val link by LinkHub.state.collectAsState()
    val power by PowerMode.state.collectAsState()
    remember { PairedStore(ctx) } // loads the saved pairing into PairedStore.paired
    val paired by PairedStore.paired.collectAsState()

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Pixlink") },
            actions = { IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, "Settings") } },
        )
    }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Connection card
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Computer, null, Modifier.size(32.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                when (val s = link) {
                                    is LinkState.Connected -> "Connected to ${s.pcName}"
                                    is LinkState.Connecting -> "Connecting to ${s.pcName}…"
                                    is LinkState.Error -> "Something went wrong"
                                    LinkState.NotPaired -> "Not paired"
                                    LinkState.Disconnected -> paired?.let { "${it.name} is offline" } ?: "Not paired"
                                },
                                style = MaterialTheme.typography.titleLarge,
                            )
                            if (link is LinkState.Error) Text((link as LinkState.Error).message, style = MaterialTheme.typography.bodySmall)
                            val reason = LinkHub.lastDisconnectReason
                            if (paired != null && link !is LinkState.Connected && reason != null) {
                                Text("Last disconnect: $reason", style = MaterialTheme.typography.bodySmall)
                            }
                            if (paired == null) Text("Open Pixlink on your PC and scan the QR code.", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Spacer(Modifier.size(16.dp))
                    if (paired == null) {
                        Button(onClick = onPair) {
                            Icon(Icons.Outlined.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text("Scan QR code")
                        }
                    } else if (link is LinkState.Error) {
                        // Usually means the PC unlinked this phone: offer a fresh pairing.
                        Button(onClick = {
                            PairedStore(ctx).pc = null
                            LinkService.stop(ctx)
                            onPair()
                        }) { Text("Pair again") }
                    } else {
                        OutlinedButton(onClick = {
                            PairedStore(ctx).pc = null
                            LinkService.stop(ctx)
                        }) { Text("Forget this PC") }
                    }
                }
            }

            // Updates install by themselves; this card only shows progress or a step that needs you.
            val updateStatus by AppUpdater.status.collectAsState()
            val available by UpdateChecker.available.collectAsState()
            LaunchedEffect(Unit) { AppUpdater.autoUpdate(ctx) }
            UpdateCard(updateStatus, available)

            Text("Setup", style = MaterialTheme.typography.titleMedium)

            SetupRow(
                icon = Icons.Outlined.Notifications,
                title = "Notification access",
                subtitle = "Needed for notifications and replying to texts",
                done = NotificationBridge.isEnabled,
            ) { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }

            SetupRow(
                icon = Icons.Outlined.Call,
                title = "Calls, contacts & photos",
                subtitle = "Allow Pixlink to place calls and show your gallery",
                done = false,
                onClick = onGrantPermissions,
            )

            SetupRow(
                icon = Icons.Outlined.Bolt,
                title = "Power mode",
                subtitle = when (power) {
                    PowerMode.State.Ready -> "On — screen control, hotspot toggle, call history, all files"
                    else -> "Optional — unlock screen control, hotspot toggle and more"
                },
                done = power == PowerMode.State.Ready,
                onClick = onPowerMode,
            )
        }
    }
}

@Composable
private fun SetupRow(icon: ImageVector, title: String, subtitle: String, done: Boolean, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        ListItem(
            leadingContent = { Icon(icon, null) },
            headlineContent = { Text(title) },
            supportingContent = { Text(subtitle) },
            trailingContent = { if (done) Icon(Icons.Outlined.CheckCircle, "Done", tint = MaterialTheme.colorScheme.primary) },
        )
    }
}

@Composable
private fun UpdateCard(status: AppUpdater.Status, available: UpdateChecker.Update?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val (title, detail, action) = when (status) {
        is AppUpdater.Status.Downloading -> Triple("Updating Pixlink", "Downloading ${status.version}… ${(status.progress * 100).toInt()}%", null)
        is AppUpdater.Status.Installing -> Triple("Updating Pixlink", "Installing ${status.version}. Pixlink restarts by itself.", null)
        is AppUpdater.Status.WaitingForConfirm -> Triple("One tap to finish updating", "Tap Update on the Android prompt. Future updates install by themselves.", null)
        AppUpdater.Status.NeedsPermission -> Triple(
            "Turn on automatic updates",
            "Allow Pixlink to install updates once, and new versions install by themselves.",
            "Allow" to {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")))
            },
        )
        is AppUpdater.Status.Failed -> Triple(
            "Update didn't install", status.message,
            status.apkUrl?.let { url -> "Download" to { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
        )
        AppUpdater.Status.Idle -> if (available != null) Triple(
            "Update available", "Pixlink ${available.version} (you have ${UpdateChecker.currentVersion})",
            "Install" to { scope.launch { AppUpdater.install(ctx, available) }; Unit },
        ) else return
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(detail, style = MaterialTheme.typography.bodySmall)
                }
                action?.let { (label, onClick) -> Button(onClick = onClick) { Text(label) } }
            }
            if (status is AppUpdater.Status.Downloading) {
                LinearProgressIndicator(progress = { status.progress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        }
    }
}
