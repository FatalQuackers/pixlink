package app.pixlink

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import app.pixlink.power.PowerMode

class PixlinkApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannels()
        PowerMode.init(this)
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_LINK, getString(R.string.channel_link), NotificationManager.IMPORTANCE_MIN)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MIRROR, getString(R.string.channel_mirror), NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        const val CHANNEL_LINK = "link"
        const val CHANNEL_MIRROR = "mirror"

        lateinit var instance: PixlinkApp
            private set

        /** Pixlink is Pixel-only. Debug builds skip the check so emulators work. */
        val isSupportedDevice: Boolean
            get() = BuildConfig.DEBUG ||
                (Build.MANUFACTURER.equals("Google", ignoreCase = true) && Build.MODEL.contains("Pixel", ignoreCase = true))
    }
}
