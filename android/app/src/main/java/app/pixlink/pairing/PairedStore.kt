package app.pixlink.pairing

import android.content.Context
import androidx.core.content.edit

/** The one PC this phone is paired with. */
data class PairedPc(
    val host: String,
    val port: Int,
    val fingerprint: String,
    val name: String,
    val id: String,
)

/** Small key-value store for pairing and user settings. */
class PairedStore(context: Context) {
    private val prefs = context.getSharedPreferences("pixlink", Context.MODE_PRIVATE)

    var pc: PairedPc?
        get() {
            val host = prefs.getString("host", null) ?: return null
            return PairedPc(
                host = host,
                port = prefs.getInt("port", 47100),
                fingerprint = prefs.getString("fp", null) ?: return null,
                name = prefs.getString("pcName", "PC")!!,
                id = prefs.getString("pcId", "")!!,
            )
        }
        set(value) = prefs.edit {
            if (value == null) {
                remove("host"); remove("port"); remove("fp"); remove("pcName"); remove("pcId")
            } else {
                putString("host", value.host)
                putInt("port", value.port)
                putString("fp", value.fingerprint)
                putString("pcName", value.name)
                putString("pcId", value.id)
            }
        }

    /** Updates the remembered IP when the PC's address changes (found via mDNS). */
    fun updateHost(host: String) = prefs.edit { putString("host", host) }

    /** Hotspot details the user enters once, so the PC can auto-join. */
    var hotspotSsid: String?
        get() = prefs.getString("hotspotSsid", null)
        set(v) = prefs.edit { putString("hotspotSsid", v) }

    var hotspotPassword: String?
        get() = prefs.getString("hotspotPassword", null)
        set(v) = prefs.edit { putString("hotspotPassword", v) }
}
