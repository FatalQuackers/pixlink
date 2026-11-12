package app.pixlink.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.pixlink.pairing.PairedStore
import app.pixlink.update.UpdateChecker
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onChooseApps: () -> Unit) {
    val ctx = LocalContext.current
    val store = remember { PairedStore(ctx) }
    val scope = rememberCoroutineScope()

    var deviceName by remember { mutableStateOf(store.deviceName) }
    var mirror by remember { mutableStateOf(store.mirrorNotifications) }
    var hide by remember { mutableStateOf(store.hideContent) }
    var ring by remember { mutableStateOf(store.allowRing) }
    var clip by remember { mutableStateOf(store.allowClipboard) }
    var media by remember { mutableStateOf(store.allowMediaControls) }
    var dnd by remember { mutableStateOf(store.allowDndControl) }
    var autoUpdate by remember { mutableStateOf(store.autoUpdate) }
    var ssid by remember { mutableStateOf(store.hotspotSsid ?: "") }
    var pass by remember { mutableStateOf(store.hotspotPassword ?: "") }
    var checking by remember { mutableStateOf(false) }
    var updateStatus by remember { mutableStateOf<String?>(null) }
    var folders by remember { mutableIntStateOf(ctx.contentResolver.persistedUriPermissions.size) }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        ctx.contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        folders = ctx.contentResolver.persistedUriPermissions.size
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
        )
    }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(bottom = 32.dp),
        ) {
            Section("This phone")
            Column(Modifier.padding(horizontal = 16.dp)) {
                OutlinedTextField(
                    deviceName, { deviceName = it; store.deviceName = it.trim() }, Modifier.fillMaxWidth(),
                    label = { Text("Name shown on your PC") }, placeholder = { Text(android.os.Build.MODEL) }, singleLine = true,
                )
            }

            Section("Notifications")
            SwitchRow("Send notifications to my PC", null, mirror) { mirror = it; store.mirrorNotifications = it }
            ListItem(
                headlineContent = { Text("Choose apps") },
                supportingContent = {
                    val n = store.blockedApps.size
                    Text(if (n == 0) "All apps are sent" else "$n app${if (n == 1) "" else "s"} not sent")
                },
                trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null) },
                modifier = Modifier.clickable(onClick = onChooseApps),
            )
            SwitchRow(
                "Hide private message text",
                "Your PC shows \"New notification\" for notifications marked private",
                hide,
            ) { hide = it; store.hideContent = it }

            Section("Your PC can")
            SwitchRow("Ring this phone", "Find it at full volume, even on silent", ring) { ring = it; store.allowRing = it }
            ConnectViaRow(store)
            SwitchRow("Let my PC copy to this phone", "Copy on your PC, paste here", clip) { clip = it; store.allowClipboard = it }
            var codes by remember { mutableStateOf(store.sendCodes) }
            SwitchRow("Copy sign-in codes to my PC", "Verification codes from your texts and apps, ready to paste", codes) { codes = it; store.sendCodes = it }
            var onOpen by remember { mutableStateOf(store.sendClipboardOnOpen) }
            SwitchRow("Send my clipboard when I open Pixlink", "Android only lets apps read the clipboard while they're open", onOpen) { onOpen = it; store.sendClipboardOnOpen = it }
            SwitchRow("Control music and videos", "Play, pause and skip from your PC", media) { media = it; store.allowMediaControls = it }
            SwitchRow("Turn Do Not Disturb on and off", null, dnd) { dnd = it; store.allowDndControl = it }

            Section("Hotspot")
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Your PC asks for these the first time you use Hotspot. You can also set them here.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(ssid, { ssid = it; store.hotspotSsid = it }, Modifier.fillMaxWidth(), label = { Text("Hotspot name") }, singleLine = true)
                OutlinedTextField(
                    pass, { pass = it; store.hotspotPassword = it }, Modifier.fillMaxWidth(),
                    label = { Text("Hotspot password") }, visualTransformation = PasswordVisualTransformation(), singleLine = true,
                )
            }

            Section("Files")
            ListItem(
                headlineContent = { Text("Shared folders") },
                supportingContent = { Text("$folders folder(s) your PC can browse. With Power mode, it can browse everything.") },
                trailingContent = { OutlinedButton(onClick = { folderPicker.launch(null) }) { Text("Add") } },
            )

            Section("Staying connected")
            ListItem(
                headlineContent = { Text("Keep running in the background") },
                supportingContent = { Text("Open App info → Battery → Unrestricted, so Android doesn't disconnect Pixlink.") },
                trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null) },
                modifier = Modifier.clickable {
                    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
                },
            )

            Section("Updates")
            SwitchRow(
                "Install updates automatically",
                "New versions download and install by themselves. With Power mode, completely silently.",
                autoUpdate,
            ) { autoUpdate = it; store.autoUpdate = it }
            var early by remember { mutableStateOf(store.earlyUpdates) }
            SwitchRow(
                "Get early updates",
                "New versions arrive a day before everyone else. They're tested less, so there may be bugs.",
                early,
            ) { early = it; store.earlyUpdates = it }
            ListItem(
                headlineContent = { Text("Pixlink ${UpdateChecker.currentVersion}") },
                supportingContent = { Text(updateStatus ?: "Pixlink checks for updates when you open it.") },
                trailingContent = {
                    OutlinedButton(enabled = !checking, onClick = {
                        checking = true
                        scope.launch {
                            updateStatus = try {
                                val u = UpdateChecker.check()
                                if (u == null) "You're on the latest version." else "Pixlink ${u.version} is available. It installs from the home screen."
                            } catch (e: Exception) {
                                "Couldn't check for updates. Are you online?"
                            }
                            checking = false
                        }
                    }) { Text(if (checking) "Checking…" else "Check") }
                },
            )

            Section("About")
            ListItem(
                headlineContent = { Text("Show the welcome tour again") },
                supportingContent = { Text("Shows next time you open Pixlink without a linked PC") },
                modifier = Modifier.clickable { store.onboardingDone = false },
            )
            ListItem(
                headlineContent = { Text("Pixlink on GitHub") },
                supportingContent = { Text("Open source (MIT). Not affiliated with Google or Microsoft.") },
                modifier = Modifier.clickable {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/FatalQuackers/pixlink")))
                },
            )
        }
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 16.dp))
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Switch(checked, onChange) },
        modifier = Modifier.clickable { onChange(!checked) },
    )
}


/** Wi-Fi, Bluetooth, or both (Wi-Fi first, Bluetooth when there's no shared Wi-Fi). */
@OptIn(ExperimentalMaterial3Api::class)
@androidx.compose.runtime.Composable
private fun ConnectViaRow(store: app.pixlink.pairing.PairedStore) {
    var via by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(store.connectVia) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        androidx.compose.material3.Text("Connect using", style = androidx.compose.material3.MaterialTheme.typography.bodyLarge)
        androidx.compose.material3.Text(
            "Bluetooth works without shared Wi-Fi, but screen sharing needs Wi-Fi. Pair this phone with your PC in Bluetooth settings first.",
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val options = listOf("auto" to "Both (Wi-Fi first, then Bluetooth)", "wifi" to "Wi-Fi only", "bluetooth" to "Bluetooth only")
        options.forEach { (key, label) ->
            androidx.compose.foundation.layout.Row(
                androidx.compose.ui.Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                androidx.compose.material3.RadioButton(
                    selected = via == key,
                    onClick = { via = key; store.connectVia = key; app.pixlink.link.LinkService.start(ctx) },
                )
                androidx.compose.material3.Text(label)
            }
        }
    }
}
