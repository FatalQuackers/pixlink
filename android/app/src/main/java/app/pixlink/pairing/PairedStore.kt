package app.pixlink.pairing

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A PC this phone is paired with. A phone can be paired with several (e.g. work and home). */
@Serializable
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
        if (!loaded) { loaded = true; migrateSinglePc(); _paired.value = pcs }
    }

    /** Every paired PC. Stored as JSON; written with commit() so pairing survives a sudden kill. */
    var pcs: List<PairedPc>
        get() = prefs.getString("pcs", null)
            ?.let { runCatching { json.decodeFromString(ListSerializer(PairedPc.serializer()), it) }.getOrNull() }
            ?: emptyList()
        private set(value) {
            prefs.edit(commit = true) { putString("pcs", json.encodeToString(ListSerializer(PairedPc.serializer()), value)) }
            _paired.value = value
        }

    fun pc(id: String): PairedPc? = pcs.firstOrNull { it.id == id }

    /** Adds a PC, or replaces it if it was paired before. */
    fun add(pc: PairedPc) {
        pcs = pcs.filterNot { it.id == pc.id || it.fingerprint == pc.fingerprint } + pc
    }

    fun remove(id: String) {
        pcs = pcs.filterNot { it.id == id }
    }

    /** Updates a PC's remembered IP when it shows up at a new address. */
    fun updateHost(id: String, host: String) {
        pcs = pcs.map { if (it.id == id) it.copy(host = host) else it }
    }

    /** Earlier versions stored one PC in separate keys; move it into the list. */
    private fun migrateSinglePc() {
        val host = prefs.getString("host", null) ?: return
        val fp = prefs.getString("fp", null) ?: return
        val old = PairedPc(
            host = host,
            port = prefs.getInt("port", 47100),
            fingerprint = fp,
            name = prefs.getString("pcName", "PC") ?: "PC",
            id = prefs.getString("pcId", null)?.takeIf { it.isNotEmpty() } ?: fp,
        )
        if (pcs.none { it.id == old.id }) {
            prefs.edit(commit = true) { putString("pcs", json.encodeToString(ListSerializer(PairedPc.serializer()), pcs + old)) }
        }
        prefs.edit(commit = true) { remove("host"); remove("port"); remove("fp"); remove("pcName"); remove("pcId") }
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

    /** Copy sign-in codes from notifications to the PC's clipboard. */
    var sendCodes: Boolean
        get() = prefs.getBoolean("sendCodes", true)
        set(v) = prefs.edit { putBoolean("sendCodes", v) }

    /** Send what you copied on the phone whenever you open Pixlink. */
    var sendClipboardOnOpen: Boolean
        get() = prefs.getBoolean("sendClipboardOnOpen", true)
        set(v) = prefs.edit { putBoolean("sendClipboardOnOpen", v) }

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
        private val json = Json { ignoreUnknownKeys = true }
        private val _paired = MutableStateFlow<List<PairedPc>>(emptyList())
        @Volatile private var loaded = false

        /** The paired PCs, live — the UI collects this so it updates the moment pairing changes. */
        val paired: StateFlow<List<PairedPc>> = _paired.asStateFlow()
    }
}
