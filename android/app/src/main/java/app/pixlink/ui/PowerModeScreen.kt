package app.pixlink.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.pixlink.power.PowerMode
import app.pixlink.power.PowerMode.State

/** Step-by-step Power mode setup. Each step only shows once the previous one is done. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PowerModeScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val state by PowerMode.state.collectAsState()

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Power mode") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
        )
    }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Power mode unlocks screen control, the hotspot toggle, call history and full file access. " +
                    "It uses the free Shizuku app and takes about 3 minutes, once.",
                style = MaterialTheme.typography.bodyLarge,
            )

            Step(1, "Install Shizuku", done = state != State.ShizukuNotInstalled) {
                Button(onClick = {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=moe.shizuku.privileged.api")))
                }) { Text("Get Shizuku") }
            }

            if (state != State.ShizukuNotInstalled) Step(
                2, "Turn on Wireless debugging",
                done = state !in listOf(State.ShizukuNotRunning),
                body = "Settings → About phone → tap Build number 7 times. " +
                    "Then Settings → System → Developer options → Wireless debugging → On.",
            ) {
                OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }) {
                    Text("Open Developer options")
                }
            }

            if (state != State.ShizukuNotInstalled) Step(
                3, "Start Shizuku",
                done = state !in listOf(State.ShizukuNotRunning),
                body = "Open Shizuku → Start via Wireless debugging → Pairing, and enter the code from the notification. " +
                    "After a phone restart you'll need to tap Start again.",
            ) {
                OutlinedButton(onClick = {
                    ctx.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let(ctx::startActivity)
                }) { Text("Open Shizuku") }
            }

            if (state == State.NeedsPermission || state == State.Connecting || state == State.Ready) Step(
                4, "Allow Pixlink", done = state == State.Ready,
            ) {
                Button(onClick = PowerMode::requestPermission, enabled = state == State.NeedsPermission) { Text("Grant") }
            }

            if (state == State.Ready) Text("Power mode is on.", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun Step(n: Int, title: String, done: Boolean, body: String? = null, action: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${if (done) "✓" else "$n."} $title", style = MaterialTheme.typography.titleMedium)
            body?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (!done) action()
        }
    }
}
