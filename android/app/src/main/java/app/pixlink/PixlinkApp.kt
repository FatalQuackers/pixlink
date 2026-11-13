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
        app.pixlink.update.UpdateChecker.early = app.pixlink.pairing.PairedStore(this).earlyUpdates
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

        /** Every Android phone (13+, set by minSdk) is supported. */
        val isSupportedDevice: Boolean
            get() = true

        private val isEmulator: Boolean
            get() = Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.contains("emulator") ||
                Build.MODEL.startsWith("sdk_gphone") || Build.HARDWARE in setOf("ranchu", "goldfish")
    }
}
