package app.pixlink.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.pixlink.mirror.ControlService
import app.pixlink.notifications.NotificationBridge

/** What Pixlink has been allowed to do, re-read whenever the user comes back to the app. */
data class PermissionState(
    val notifications: Boolean,
    val callsContacts: Boolean,
    val photos: Boolean,
    val messages: Boolean,
    val control: Boolean,
) {
    val coreDone get() = notifications && callsContacts && photos
}

object Permissions {
    fun granted(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    fun read(ctx: Context) = PermissionState(
        notifications = NotificationBridge.isEnabled || isListenerEnabled(ctx),
        callsContacts = granted(ctx, Manifest.permission.READ_CONTACTS) && granted(ctx, Manifest.permission.CALL_PHONE),
        photos = granted(ctx, if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE),
        messages = granted(ctx, Manifest.permission.READ_SMS),
        control = ControlService.isEnabled(ctx),
    )

    /** The listener can be on before our service has bound, so ask Settings directly too. */
    private fun isListenerEnabled(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
        return flat.split(':').any { ComponentName.unflattenFromString(it)?.packageName == ctx.packageName }
    }

    /** Opens Pixlink's own switch when Android allows it, so there's nothing to scroll for. */
    fun openNotificationAccess(ctx: Context) {
        val direct = if (Build.VERSION.SDK_INT >= 30) Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(ctx, NotificationBridge::class.java).flattenToString())
        else null
        val list = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        start(ctx, direct, list)
    }

    fun openAccessibility(ctx: Context) {
        val direct = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
            .putExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName(ctx, ControlService::class.java).flattenToString())
        start(ctx, direct, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    /** App info, where the ⋮ menu has "Allow restricted settings" for apps installed outside Play. */
    fun openAppInfo(ctx: Context) {
        start(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")), null)
    }

    private fun start(ctx: Context, first: Intent?, fallback: Intent?) {
        for (i in listOfNotNull(first, fallback)) {
            try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Exception) { }
        }
    }
}

/** Re-reads permissions every time the screen resumes (coming back from Settings). */
@Composable
fun rememberPermissionState(ctx: Context): PermissionState {
    var tick by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        lifecycle.lifecycle.addObserver(obs)
        onDispose { lifecycle.lifecycle.removeObserver(obs) }
    }
    return remember(tick) { Permissions.read(ctx) }
}

enum class SpecialAccess { Notifications, Control }

/**
 * Explains the Settings screen before sending the user there: where Pixlink is in the list,
 * and what to do when Android says "Restricted setting" (apps installed outside the Play Store).
 */
@Composable
fun SpecialAccessDialog(kind: SpecialAccess, ctx: Context, onDismiss: () -> Unit) {
    val what = if (kind == SpecialAccess.Notifications) "Notification access" else "Accessibility"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Turn on $what") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Step("1", if (kind == SpecialAccess.Notifications)
                    "On the next screen, find Pixlink. If you see a list, scroll down until you reach it."
                else "On the next screen, tap Pixlink. If you see a list, scroll down to \"Downloaded apps\" or \"Installed apps\" and tap Pixlink there.")
                Step("2", "Turn Pixlink on, then tap Allow.")
                Step("3", "Greyed out, or Android says \"Restricted setting\"? Tap \"Open app info\" below, tap ⋮ at the top right, choose \"Allow restricted settings\", then try again.")
                Text(
                    "Android does this for every app installed outside the Play Store.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (kind == SpecialAccess.Notifications) Permissions.openNotificationAccess(ctx) else Permissions.openAccessibility(ctx)
                onDismiss()
            }) { Text("Open settings") }
        },
        dismissButton = {
            TextButton(onClick = { Permissions.openAppInfo(ctx); onDismiss() }) { Text("Open app info") }
        },
    )
}

@Composable
private fun Step(n: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(n, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(text, modifier = Modifier.padding(end = 4.dp))
    }
}
