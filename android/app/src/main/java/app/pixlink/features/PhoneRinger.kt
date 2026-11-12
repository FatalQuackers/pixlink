package app.pixlink.features

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import app.pixlink.MainActivity
import app.pixlink.PixlinkApp
import app.pixlink.R

/**
 * "Find my phone": plays the alarm sound at full volume, even on silent, for up to 30 seconds.
 * Stops when the PC says so, when the time runs out, or when you tap the notification.
 */
object PhoneRinger {
    private var player: MediaPlayer? = null
    private var savedVolume = -1
    private val main = Handler(Looper.getMainLooper())
    private const val NOTIF_ID = 4
    const val ACTION_STOP = "app.pixlink.STOP_RING"

    fun start(context: Context) = main.post {
        if (player != null) return@post
        val app = context.applicationContext
        val audio = app.getSystemService(AudioManager::class.java)
        savedVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)

        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        player = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
            setDataSource(app, uri)
            isLooping = true
            prepare()
            start()
        }

        val stop = PendingIntent.getActivity(
            app, 5, Intent(app, MainActivity::class.java).setAction(ACTION_STOP).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(app, PixlinkApp.CHANNEL_MIRROR)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle("Your PC is ringing this phone")
            .setContentText("Tap to stop")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentIntent(stop)
            .setAutoCancel(true)
            .build()
        app.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)

        main.postDelayed({ stop(app) }, 30_000)
    }

    fun stop(context: Context) = main.post {
        val p = player ?: return@post
        runCatching { p.stop(); p.release() }
        player = null
        val audio = context.applicationContext.getSystemService(AudioManager::class.java)
        if (savedVolume >= 0) audio.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0)
        context.applicationContext.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
    }
}
