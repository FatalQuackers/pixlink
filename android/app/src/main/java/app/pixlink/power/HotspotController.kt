package app.pixlink.power

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import app.pixlink.link.LinkHub
import app.pixlink.link.msg
import app.pixlink.pairing.PairedStore
import kotlinx.serialization.json.put

/**
 * Hotspot: with Power mode, a real on/off toggle. Without it, we open the phone's
 * hotspot settings so it's one tap. Either way the PC gets the network name and
 * password (entered once in Pixlink settings) so it can join automatically.
 */
class HotspotController(private val context: Context, private val store: PairedStore) {

    suspend fun set(enabled: Boolean) {
        if (PowerMode.isReady) {
            val ok = PowerMode.setWifiTethering(enabled)
            if (!ok && enabled) openSettings()
        } else if (enabled) {
            openSettings()
        }
        reportState(enabled)
    }

    fun reportState(enabled: Boolean? = null) {
        LinkHub.send(msg("hotspot.state") {
            enabled?.let { put("enabled", it) }
            put("toggleSupported", PowerMode.isReady)
            store.hotspotSsid?.let { put("ssid", it) }
            store.hotspotPassword?.let { put("password", it) }
        })
    }

    private fun openSettings() {
        val tether = Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.TetherSettings"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ok = runCatching { context.startActivity(tether) }.isSuccess
        if (!ok) context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
