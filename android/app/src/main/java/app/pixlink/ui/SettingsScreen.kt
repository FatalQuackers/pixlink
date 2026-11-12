package app.pixlink.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val store = remember { PairedStore(ctx) }
    var ssid by remember { mutableStateOf(store.hotspotSsid ?: "") }
    var pass by remember { mutableStateOf(store.hotspotPassword ?: "") }
    var checking by remember { mutableStateOf(false) }
    var updateStatus by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        ctx.contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
        )
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Hotspot", style = MaterialTheme.typography.titleMedium)
            Text(
                "Enter your phone's hotspot name and password (Settings → Network → Hotspot) so your PC can join automatically.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(ssid, { ssid = it; store.hotspotSsid = it }, Modifier.fillMaxWidth(), label = { Text("Hotspot name") })
            OutlinedTextField(
                pass, { pass = it; store.hotspotPassword = it }, Modifier.fillMaxWidth(),
                label = { Text("Hotspot password") }, visualTransformation = PasswordVisualTransformation(),
            )

            Text("Shared folders", style = MaterialTheme.typography.titleMedium)
            Text(
                "Pick folders your PC can browse. With Power mode on, your PC can browse all storage.",
                style = MaterialTheme.typography.bodyMedium,
            )
            val count = ctx.contentResolver.persistedUriPermissions.size
            Text("$count folder(s) shared", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { folderPicker.launch(null) }) { Text("Add a folder") }

            Text("Updates", style = MaterialTheme.typography.titleMedium)
            Text("You have Pixlink ${UpdateChecker.currentVersion}", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(enabled = !checking, onClick = {
                checking = true
                scope.launch {
                    updateStatus = try {
                        val u = UpdateChecker.check()
                        if (u == null) "You're on the latest version." else "Pixlink ${u.version} is available. Tap Download on the home screen."
                    } catch (e: Exception) {
                        "Couldn't check for updates. Are you online?"
                    }
                    checking = false
                }
            }) { Text(if (checking) "Checking…" else "Check for updates") }
            updateStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
