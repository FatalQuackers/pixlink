package app.pixlink.ui

import android.Manifest
import android.companion.AssociationInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.pixlink.PixlinkApp
import app.pixlink.companion.QuietMode
import app.pixlink.pairing.PairedStore
import kotlinx.coroutines.launch

/**
 * "Hide the always-on notification": quiet mode (opt-in). Needs the PC's opt-in too.
 */
@Composable
fun QuietModeSettings() {
    val ctx = LocalContext.current
    val store = remember { PairedStore(ctx) }
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(QuietMode.isActive(ctx)) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun created(info: AssociationInfo) {
        scope.launch {
            message = "Pairing with your PC over Bluetooth. Accept the request on both."
            val error = QuietMode.finish(ctx, info)
            busy = false
            on = error == null
            message = error ?: "On. Tethr Link wakes up whenever your PC is nearby."
        }
    }

    val confirm = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) { busy = false; message = null }
        // The association arrives in the callback passed to requestAssociation.
    }

    fun associate() {
        busy = true
        message = "Looking for your PC…"
        QuietMode.requestAssociation(
            ctx,
            onPending = { confirm.launch(IntentSenderRequest.Builder(it).build()) },
            onCreated = ::created,
            onError = { busy = false; message = it },
        )
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) associate() else { busy = false; message = "Tethr Link needs the Nearby devices permission for this." }
    }

    fun turnOn() {
        message = null
        when {
            !QuietMode.isSupported(ctx) -> message = "This phone doesn't support it. You can still hide the notification below."
            store.pcs.none { it.wake } -> message =
                "First, on your PC open Tethr Link → Settings → Connections and turn on Let my phone find this PC over Bluetooth. Then try again (with the PC connected)."
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ->
                permission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            else -> associate()
        }
    }

    ListItem(
        headlineContent = { Text("Hide the always-on notification") },
        supportingContent = {
            Text("Tethr Link runs without it and wakes up whenever your PC is nearby, using Bluetooth. Your PC needs Bluetooth and this turned on in its settings too.")
        },
        trailingContent = {
            if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            else Switch(on, { want -> if (want) turnOn() else { QuietMode.disable(ctx); on = false; message = null } })
        },
        modifier = Modifier.clickable(enabled = !busy) { if (on) { QuietMode.disable(ctx); on = false; message = null } else turnOn() },
    )
    AnimatedVisibility(message != null) {
        Text(
            message ?: "",
            style = MaterialTheme.typography.bodySmall,
            color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
    if (!on) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                ctx.startActivity(
                    Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                        .putExtra(Settings.EXTRA_CHANNEL_ID, PixlinkApp.CHANNEL_LINK)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }) { Text("Or just hide it in Android settings") }
            Spacer(Modifier.width(4.dp))
        }
    }
}
