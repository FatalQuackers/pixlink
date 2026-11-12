package app.pixlink.ui

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import app.pixlink.pairing.PairedStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class AppEntry(val pkg: String, val label: String, val icon: ImageBitmap?)

/** Pick which apps' notifications go to the PC. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppFilterScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val store = remember { PairedStore(ctx) }
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var blocked by remember { mutableStateOf(store.blockedApps) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            // Apps you can open from the launcher: the ones that post notifications people care about.
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.activityInfo.applicationInfo }
                .distinctBy { it.packageName }
                .filter { it.packageName != ctx.packageName }
                .map { info ->
                    AppEntry(
                        info.packageName,
                        pm.getApplicationLabel(info).toString(),
                        runCatching { pm.getApplicationIcon(info).toBitmap(96, 96, Bitmap.Config.ARGB_8888).asImageBitmap() }.getOrNull(),
                    )
                }
                .sortedBy { it.label.lowercase() }
        }
    }

    fun toggle(pkg: String, send: Boolean) {
        blocked = if (send) blocked - pkg else blocked + pkg
        store.blockedApps = blocked
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Choose apps") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
        )
    }) { pad ->
        val list = apps
        if (list == null) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            item {
                OutlinedTextField(
                    query, { query = it }, Modifier.padding(16.dp),
                    placeholder = { Text("Search apps") }, singleLine = true,
                )
            }
            items(list.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }, key = { it.pkg }) { app ->
                val send = app.pkg !in blocked
                ListItem(
                    leadingContent = { app.icon?.let { Image(it, null, Modifier.size(36.dp)) } },
                    headlineContent = { Text(app.label) },
                    supportingContent = { Text(if (send) "Sent to your PC" else "Not sent") },
                    trailingContent = { Switch(send, { toggle(app.pkg, it) }) },
                    modifier = Modifier.clickable { toggle(app.pkg, !send) },
                )
            }
        }
    }
}
