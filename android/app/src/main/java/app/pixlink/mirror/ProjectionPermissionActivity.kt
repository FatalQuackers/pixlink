package app.pixlink.mirror

import android.app.Activity
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import app.pixlink.PixlinkApp
import app.pixlink.R
import app.pixlink.power.PowerMode
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import app.pixlink.link.LinkHub
import app.pixlink.link.msg
import kotlinx.serialization.json.put

/**
 * Invisible activity that shows Android's "Start casting?" prompt.
 * Android requires this prompt every session — there's no way around it for normal apps.
 */
class ProjectionPermissionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST && resultCode == RESULT_OK && data != null) {
            MirrorService.start(
                this, resultCode, data,
                intent.getIntExtra(EXTRA_MAX, 1280),
                intent.getIntExtra(EXTRA_BITRATE, 6_000_000),
                intent.getIntExtra(EXTRA_FPS, 60),
            )
        } else {
            LinkHub.send(msg("mirror.stop") { put("reason", "denied") })
        }
        finish()
    }

    companion object {
        private const val REQUEST = 7
        private const val EXTRA_MAX = "max"
        private const val EXTRA_BITRATE = "bitrate"
        private const val EXTRA_FPS = "fps"

        /**
         * Android blocks apps from opening screens while in the background. With Power mode
         * we open the prompt via the shell; otherwise we post a "Tap to share" notification.
         */
        suspend fun launch(context: Context, maxSize: Int, bitrate: Int, fps: Int) {
            if (PowerMode.isReady) {
                val cmp = "${context.packageName}/${ProjectionPermissionActivity::class.java.name}"
                PowerMode.exec("am start -n $cmp --ei $EXTRA_MAX $maxSize --ei $EXTRA_BITRATE $bitrate --ei $EXTRA_FPS $fps")
                return
            }
            val intent = Intent(context, ProjectionPermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_MAX, maxSize)
                .putExtra(EXTRA_BITRATE, bitrate)
                .putExtra(EXTRA_FPS, fps)
            val pi = PendingIntent.getActivity(context, 3, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = NotificationCompat.Builder(context, PixlinkApp.CHANNEL_MIRROR)
                .setSmallIcon(R.drawable.ic_stat_link)
                .setContentTitle("Your PC wants to show your screen")
                .setContentText("Tap to start sharing")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            context.getSystemService(NotificationManager::class.java).notify(PROMPT_NOTIF_ID, n)
        }

        private const val PROMPT_NOTIF_ID = 3
    }
}
