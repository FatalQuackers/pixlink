package app.pixlink.update

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.app.NotificationCompat
import app.pixlink.PixlinkApp
import app.pixlink.R
import app.pixlink.link.LinkService
import app.pixlink.pairing.PairedStore

/** Result of a PackageInstaller update session. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // First update only: Android wants a one-time confirmation.
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                AppUpdater.setStatus(AppUpdater.Status.WaitingForConfirm(""))
                runCatching { context.startActivity(confirm) } // works when Pixlink is open
                val pi = PendingIntent.getActivity(context, 9, confirm, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                val n = NotificationCompat.Builder(context, PixlinkApp.CHANNEL_MIRROR)
                    .setSmallIcon(R.drawable.ic_stat_link)
                    .setContentTitle("Pixlink update ready")
                    .setContentText("Tap to install. Future updates install by themselves.")
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build()
                context.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
            }
            PackageInstaller.STATUS_SUCCESS -> AppUpdater.setStatus(AppUpdater.Status.Idle)
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Install failed"
                val hint = if (msg.contains("INCOMPATIBLE", ignoreCase = true) || msg.contains("signatures", ignoreCase = true))
                    "This update is signed differently. Uninstall Pixlink and install the new version once; after that, updates install by themselves."
                else msg
                AppUpdater.setStatus(AppUpdater.Status.Failed(hint, UpdateChecker.available.value?.apkUrl))
            }
        }
    }

    companion object { private const val NOTIF_ID = 6 }
}

/** After Pixlink updates itself, or the phone restarts, reconnect to the PC automatically. */
class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED && intent.action != Intent.ACTION_BOOT_COMPLETED) return
        context.getSystemService(NotificationManager::class.java).cancel(6)
        if (PairedStore(context).pcs.isNotEmpty()) runCatching { LinkService.start(context) }
    }
}
