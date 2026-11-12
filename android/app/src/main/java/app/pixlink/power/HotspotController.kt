package app.pixlink.power

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import app.pixlink.link.Envelope
import app.pixlink.link.LinkHub
import app.pixlink.link.msg
import app.pixlink.pairing.PairedStore
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put

/**
 * Hotspot: with Power mode, a real on/off switch the PC can flip. Without it, we open the
 * phone's hotspot settings so it's one tap. Either way the PC gets the hotspot name and
 * password so it can join automatically.
 */
class HotspotController(private val context: Context, private val store: PairedStore) {

    /** Handles hotspot.set and replies (same id) with whether the phone switched it itself. */
    suspend fun set(request: Envelope, enabled: Boolean): Envelope {
        var switched = false
        var openedSettings = false
        if (PowerMode.isReady) {
            switched = runCatching { PowerMode.setWifiTethering(enabled) }.getOrDefault(false)
        }
        if (!switched && enabled) {
            openSettings()
            openedSettings = true
        }
        return request.reply("hotspot.state") {
            put("enabled", enabled)
            put("switched", switched)
            put("openedSettings", openedSettings)
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

    private fun openSettings() {
        val tether = Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.TetherSettings"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ok = runCatching { context.startActivity(tether) }.isSuccess
        if (!ok) runCatching {
            context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
