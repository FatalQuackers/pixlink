package app.pixlink.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pixlink.clipboard.ClipboardSync
import app.pixlink.link.LinkHub
import app.pixlink.link.LinkService
import app.pixlink.link.LinkState
import app.pixlink.link.msg
import kotlinx.serialization.json.put
import app.pixlink.pairing.PairedPc
import app.pixlink.pairing.PairedStore
import app.pixlink.power.PowerMode
import app.pixlink.update.AppUpdater
import app.pixlink.update.UpdateChecker
import kotlinx.coroutines.launch

private val Green = Color(0xFF1E9E63)
private val Amber = Color(0xFFE89B1A)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onPair: () -> Unit,
    onPowerMode: () -> Unit,
    onSettings: () -> Unit,
    onGrantPermissions: () -> Unit,
    onGrantSms: () -> Unit = {},
    onActivity: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val states by LinkHub.states.collectAsState()
    val power by PowerMode.state.collectAsState()
    remember { PairedStore(ctx) } // loads the saved pairings into PairedStore.paired
    val pcs by PairedStore.paired.collectAsState()
    val perms = rememberPermissionState(ctx)
    var dialog by remember { mutableStateOf<SpecialAccess?>(null) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    dialog?.let { SpecialAccessDialog(it, ctx) { dialog = null } }
    val updating by AppUpdater.status.collectAsState()

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Tethr Link", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = onActivity) { Icon(Icons.Outlined.History, "Activity") }
                    IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, "Settings") }
                },
                scrollBehavior = scroll,
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val connected = pcs.filter { states[it.id] is LinkState.Connected }
            StatusHero(pcs, connected, onPair)

            androidx.compose.animation.AnimatedVisibility(
                connected.isNotEmpty(),
                enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
                exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically(),
            ) { PcActions() }

            // Updates install by themselves; this card only shows progress or a step that needs you.
            val updateStatus by AppUpdater.status.collectAsState()
            val available by UpdateChecker.available.collectAsState()
            LaunchedEffect(Unit) { AppUpdater.autoUpdate(ctx) }
            UpdateCard(updateStatus, available)

            if (pcs.isNotEmpty()) {
                SectionTitle("Your PCs")
                pcs.forEach { pc -> PcCard(pc, states[pc.id], onPair) }
                OutlinedButton(onClick = onPair, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("Link another PC")
                }
            }

            // Setup checklist: re-checked every time you come back to Pixlink.
            val items = listOf(
                Triple(perms.notifications, "Notification access", "See and reply to notifications on your PC"),
                Triple(perms.callsContacts && perms.photos, "Calls, contacts & photos", "Call from your PC and browse your gallery"),
                Triple(perms.messages, "Texts", "Your text history on your PC, no sign-in needed"),
                Triple(perms.control, "Screen control", "Tap, swipe and type on your phone from your PC"),
            )
            val done = items.count { it.first }
            SectionTitle("Setup")
            Card(Modifier.fillMaxWidth().animateContentSize()) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (done == items.size) "All set" else "$done of ${items.size} done", style = MaterialTheme.typography.titleMedium)
                        LinearProgressIndicator(progress = { done / items.size.toFloat() }, modifier = Modifier.fillMaxWidth())
                    }
                    SetupRow(Icons.Outlined.Notifications, items[0].second, items[0].third, items[0].first) { dialog = SpecialAccess.Notifications }
                    SetupRow(Icons.Outlined.Call, items[1].second, items[1].third, items[1].first, onGrantPermissions)
                    SetupRow(Icons.Outlined.ChatBubbleOutline, items[2].second, items[2].third, items[2].first, onGrantSms)
                    SetupRow(Icons.Outlined.TouchApp, items[3].second, items[3].third, items[3].first) { dialog = SpecialAccess.Control }
                    SetupRow(
                        Icons.Outlined.Bolt, "Power mode (optional)",
                        if (power == PowerMode.State.Ready) "On: automatic hotspot, lock phone, screenshots and more" else "Automatic hotspot, lock phone, screenshots, all files",
                        power == PowerMode.State.Ready, onPowerMode,
                    )
                }
            }
        }
    }
    UpdatingScreen(updating)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp, start = 4.dp))
}

/** The big card at the top: connected, waiting, or not linked yet, readable at a glance. */
@Composable
private fun StatusHero(pcs: List<PairedPc>, connected: List<PairedPc>, onPair: () -> Unit) {
    val (color, title, detail) = when {
        pcs.isEmpty() -> Triple(MaterialTheme.colorScheme.outline, "Not linked yet", "Open Tethr Link on your Windows PC, then scan its QR code or type its 5-digit code.")
        connected.isNotEmpty() -> Triple(Green, "Connected", connected.joinToString { it.name })
        else -> Triple(Amber, "Not connected", "Tethr Link connects by itself when your PC is on the same Wi-Fi with Tethr Link open.")
    }
    val tint by animateColorAsState(color, label = "hero")
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.size(56.dp).clip(CircleShape).background(tint.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Computer, null, tint = tint, modifier = Modifier.size(28.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = tint)
                    Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (pcs.isEmpty()) {
                Button(onClick = onPair, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Icon(Icons.Outlined.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text("Link a PC")
                }
            }
        }
    }
}

@Composable
private fun SetupRow(icon: ImageVector, title: String, subtitle: String, done: Boolean, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clip(MaterialTheme.shapes.medium).let { if (done) it else it },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(icon, null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            if (done) Icon(Icons.Outlined.CheckCircle, "Done", tint = Green)
            else TextButton(onClick = onClick) { Text("Turn on") }
        },
    )
}

@Composable
private fun UpdateCard(status: AppUpdater.Status, available: UpdateChecker.Update?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val (title, detail, action) = when (status) {
        is AppUpdater.Status.Downloading -> Triple("Updating Tethr Link", "Downloading ${status.version}… ${(status.progress * 100).toInt()}%", null)
        is AppUpdater.Status.Installing -> Triple("Updating Tethr Link", "Installing ${status.version}. Tethr Link restarts by itself.", null)
        is AppUpdater.Status.WaitingForConfirm -> Triple("One tap to finish updating", "Tap Update on the Android prompt. Future updates install by themselves.", null)
        AppUpdater.Status.NeedsPermission -> Triple(
            "Turn on automatic updates",
            "Allow Tethr Link to install updates once, and new versions install by themselves.",
            "Allow" to {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")))
            },
        )
        is AppUpdater.Status.Failed -> Triple(
            "Update didn't install", status.message,
            status.apkUrl?.let { url -> "Download" to { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
        )
        AppUpdater.Status.Idle -> if (available != null) Triple(
            "Update available", "Tethr Link ${available.version} (you have ${UpdateChecker.currentVersion})",
            "Install" to { scope.launch { AppUpdater.install(ctx, available) }; Unit },
        ) else return
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(detail, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                action?.let { (label, onClick) -> Button(onClick = onClick) { Text(label) } }
            }
            if (status is AppUpdater.Status.Downloading) {
                LinearProgressIndicator(progress = { status.progress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        }
    }
}

/** Quick things to do with the connected PC. */
@Composable
private fun PcActions() {
    val ctx = LocalContext.current
    var ringing by remember { mutableStateOf(false) }
    var lockSent by remember { mutableStateOf(false) }
    LaunchedEffect(ringing) { if (ringing) { kotlinx.coroutines.delay(30_000); ringing = false } }
    LaunchedEffect(lockSent) { if (lockSent) { kotlinx.coroutines.delay(2_500); lockSent = false } }

    @Composable
    fun Action(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean = false, modifier: Modifier, onClick: () -> Unit) {
        val scale by androidx.compose.animation.core.animateFloatAsState(if (active) 1.04f else 1f, label = "press")
        Card(
            onClick = onClick,
            colors = CardDefaults.cardColors(
                containerColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
            ),
            modifier = modifier.scale(scale),
        ) {
            Column(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(icon, null)
                Spacer(Modifier.size(6.dp))
                Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Action(Icons.Outlined.ContentPaste, "Clipboard", modifier = Modifier.weight(1f)) {
                ctx.startActivity(Intent(ctx, app.pixlink.clipboard.ClipboardSendActivity::class.java))
            }
            Action(Icons.Outlined.UploadFile, "Send files", modifier = Modifier.weight(1f)) {
                (ctx as? app.pixlink.MainActivity)?.pickFilesToSend()
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Action(Icons.Outlined.NotificationsActive, if (ringing) "Stop ringing" else "Find my PC", active = ringing, modifier = Modifier.weight(1f)) {
                ringing = !ringing
                LinkHub.send(msg("pc.ring") { put("ring", ringing) })
            }
            Action(Icons.Outlined.Lock, if (lockSent) "Locked" else "Lock PC", active = lockSent, modifier = Modifier.weight(1f)) {
                lockSent = true
                LinkHub.send(msg("pc.lock"))
            }
        }
    }
}

@Composable
private fun PcCard(pc: PairedPc, state: LinkState?, onPair: () -> Unit) {
    val ctx = LocalContext.current
    val connected = state is LinkState.Connected
    var menu by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }
    val (dot, label) = when (state) {
        is LinkState.Connected -> Green to (if (state.viaBluetooth) "Connected over Bluetooth" else "Connected")
        is LinkState.Connecting -> Amber to "Connecting…"
        is LinkState.Error -> MaterialTheme.colorScheme.error to state.message
        else -> MaterialTheme.colorScheme.outline to "Not connected"
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget ${pc.name}?") },
            text = { Text("This phone stops connecting to it. You can link it again anytime with its code.") },
            confirmButton = { TextButton(onClick = { confirmForget = false; LinkService.forget(ctx, pc.id) }) { Text("Forget") } },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel") } },
        )
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (connected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Computer, null, Modifier.size(28.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(pc.name, style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                    Spacer(Modifier.width(6.dp))
                    Text(label, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (state is LinkState.Error) {
                // Usually means the PC unlinked this phone: offer a fresh pairing.
                TextButton(onClick = { LinkService.forget(ctx, pc.id); onPair() }) { Text("Pair again") }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Pair again") }, onClick = { menu = false; LinkService.forget(ctx, pc.id); onPair() })
                    DropdownMenuItem(text = { Text("Forget this PC") }, onClick = { menu = false; confirmForget = true })
                }
            }
        }
    }
}
