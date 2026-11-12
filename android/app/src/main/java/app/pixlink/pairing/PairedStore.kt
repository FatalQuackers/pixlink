package app.pixlink.pairing

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    init {
        if (!loaded) { loaded = true; _paired.value = pc }
    }

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
        // commit = true: write to disk now, so pairing survives even if the app is killed right after.
        set(value) = prefs.edit(commit = true) {
            if (value == null) {
                remove("host"); remove("port"); remove("fp"); remove("pcName"); remove("pcId")
            } else {
                putString("host", value.host)
                putInt("port", value.port)
                putString("fp", value.fingerprint)
                putString("pcName", value.name)
                putString("pcId", value.id)
            }
        }.also { _paired.value = value }

    /** Updates the remembered IP when the PC's address changes (found via mDNS). */
    fun updateHost(host: String) {
        prefs.edit(commit = true) { putString("host", host) }
        _paired.value = pc
    }

    /** Whether the welcome / onboarding flow has been finished. */
    var onboardingDone: Boolean
        get() = prefs.getBoolean("onboardingDone", false)
        set(v) = prefs.edit { putBoolean("onboardingDone", v) }

    // ----- Settings -----

    /** Name your PC shows for this phone. Empty = the phone model. */
    var deviceName: String
        get() = prefs.getString("deviceName", "") ?: ""
        set(v) = prefs.edit { putString("deviceName", v) }

    var mirrorNotifications: Boolean
        get() = prefs.getBoolean("mirrorNotifications", true)
        set(v) = prefs.edit { putBoolean("mirrorNotifications", v) }

    /** Apps whose notifications are NOT sent to the PC. */
    var blockedApps: Set<String>
        get() = prefs.getStringSet("blockedApps", emptySet()) ?: emptySet()
        set(v) = prefs.edit { putStringSet("blockedApps", v) }

    /** Send "New notification" instead of the message text (for private notifications). */
    var hideContent: Boolean
        get() = prefs.getBoolean("hideContent", false)
        set(v) = prefs.edit { putBoolean("hideContent", v) }

    var allowRing: Boolean
        get() = prefs.getBoolean("allowRing", true)
        set(v) = prefs.edit { putBoolean("allowRing", v) }

    var allowClipboard: Boolean
        get() = prefs.getBoolean("allowClipboard", true)
        set(v) = prefs.edit { putBoolean("allowClipboard", v) }

    var allowMediaControls: Boolean
        get() = prefs.getBoolean("allowMediaControls", true)
        set(v) = prefs.edit { putBoolean("allowMediaControls", v) }

    var allowDndControl: Boolean
        get() = prefs.getBoolean("allowDndControl", true)
        set(v) = prefs.edit { putBoolean("allowDndControl", v) }

    var autoUpdate: Boolean
        get() = prefs.getBoolean("autoUpdate", true)
        set(v) = prefs.edit { putBoolean("autoUpdate", v) }

    /** Name sent to the PC. */
    val displayName: String get() = deviceName.ifBlank { android.os.Build.MODEL }

    /** Hotspot details the user enters once, so the PC can auto-join. */
    var hotspotSsid: String?
        get() = prefs.getString("hotspotSsid", null)
        set(v) = prefs.edit { putString("hotspotSsid", v) }

    var hotspotPassword: String?
        get() = prefs.getString("hotspotPassword", null)
        set(v) = prefs.edit { putString("hotspotPassword", v) }

    companion object {
        private val _paired = MutableStateFlow<PairedPc?>(null)
        @Volatile private var loaded = false

        /** The paired PC, live — the UI collects this so it updates the moment pairing changes. */
        val paired: StateFlow<PairedPc?> = _paired.asStateFlow()
    }
}
