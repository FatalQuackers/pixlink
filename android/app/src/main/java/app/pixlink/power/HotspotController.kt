package app.pixlink.power

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationCompat
import app.pixlink.PixlinkApp
import app.pixlink.R
import app.pixlink.link.Envelope
import app.pixlink.link.LinkHub
import app.pixlink.link.msg
import app.pixlink.pairing.PairedStore
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put

/**
 * Hotspot: with Power mode, a real on/off switch the PC can flip. Without it, Android doesn't let
 * apps turn the hotspot on, and also blocks apps from opening screens from the background, so we
 * post a notification that opens the hotspot switch in one tap.
 */
class HotspotController(private val context: Context, private val store: PairedStore) {

    /** Handles hotspot.set and replies (same id) with what happened. */
    suspend fun set(request: Envelope, enabled: Boolean): Envelope {
        var switched = false
        if (PowerMode.isReady) {
            switched = runCatching { PowerMode.setWifiTethering(enabled) }.getOrDefault(false)
        }
        val needsTap = !switched && enabled
        if (needsTap) promptToTurnOn() else cancelPrompt()
        return request.reply("hotspot.state") {
            put("enabled", enabled)
            put("switched", switched)
            put("openedSettings", needsTap) // the PC tells the user to tap the notification
            details()
        }
    }

    /** The PC asked once for the hotspot name and password; keep them here too. */
    fun saveConfig(m: Envelope) {
        m.str("ssid")?.let { store.hotspotSsid = it }
        m.str("password")?.let { store.hotspotPassword = it }
    }

    fun reportState() {
        LinkHub.send(msg("hotspot.state") { details() })
    }

    private fun JsonObjectBuilder.details() {
        put("toggleSupported", PowerMode.isReady)
        store.hotspotSsid?.takeIf { it.isNotBlank() }?.let { put("ssid", it) }
        store.hotspotPassword?.takeIf { it.isNotBlank() }?.let { put("password", it) }
    }

    /** The hotspot screen's name differs between Android versions; use the first one that exists. */
    private fun hotspotSettingsIntent(): Intent {
        val candidates = listOf(
            "com.android.settings.Settings\$WifiTetherSettingsActivity", // Wi-Fi hotspot page (Pixel, Android 12+)
            "com.android.settings.Settings\$TetherSettingsActivity",     // Hotspot & tethering
            "com.android.settings.TetherSettings",
        )
        val pm = context.packageManager
        for (cls in candidates) {
            val intent = Intent().setComponent(ComponentName("com.android.settings", cls))
            if (intent.resolveActivity(pm) != null) return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun promptToTurnOn() {
        val intent = hotspotSettingsIntent()
        // Works if Pixlink is open on the phone; otherwise Android blocks it silently…
        runCatching { context.startActivity(intent) }
        // …so also post a notification that opens the hotspot switch in one tap.
        val pi = PendingIntent.getActivity(context, 7, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, PixlinkApp.CHANNEL_MIRROR)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle("Your PC wants to use your hotspot")
            .setContentText("Tap to turn on Wi-Fi hotspot. Your PC connects by itself.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setTimeoutAfter(2 * 60 * 1000L)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
    }

    private fun cancelPrompt() {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
    }

    private companion object { const val NOTIF_ID = 8 }
}
