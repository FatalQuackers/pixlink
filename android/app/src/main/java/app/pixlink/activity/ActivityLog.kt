package app.pixlink.activity

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** What went between the phone and the PC recently: files, photos, clipboard. Kept on the phone only. */
object ActivityLog {
    enum class Kind { FileSent, FileReceived, ClipboardSent, ClipboardReceived, TextShared }

    @Serializable
    data class Entry(
        val kind: Kind,
        val title: String,
        /** A content:// address for files (to open them again), or the text for clipboard entries. */
        val detail: String? = null,
        val time: Long = System.currentTimeMillis(),
    )

    private const val MAX = 150
    private val json = Json { ignoreUnknownKeys = true }
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()
    @Volatile private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        _entries.value = prefs(ctx).getString("entries", null)
            ?.let { runCatching { json.decodeFromString(ListSerializer(Entry.serializer()), it) }.getOrNull() } ?: emptyList()
    }

    @Synchronized
    fun add(ctx: Context, entry: Entry) {
        load(ctx)
        val list = (listOf(entry) + _entries.value).take(MAX)
        _entries.value = list
        prefs(ctx).edit().putString("entries", json.encodeToString(ListSerializer(Entry.serializer()), list)).apply()
    }

    fun clear(ctx: Context) {
        _entries.value = emptyList()
        prefs(ctx).edit().remove("entries").apply()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("pixlink-activity", Context.MODE_PRIVATE)
}
