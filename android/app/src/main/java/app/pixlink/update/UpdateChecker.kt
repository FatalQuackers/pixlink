package app.pixlink.update

import android.util.Log
import app.pixlink.BuildConfig
import app.pixlink.link.Codec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/** Checks GitHub for a newer Pixlink release. */
object UpdateChecker {

    data class Update(val version: String, val apkUrl: String, val pageUrl: String)

    private val BUILD_MARKER = Regex("<!--\\s*build:(\\d+)\\s*-->")
    private const val LATEST = "https://api.github.com/repos/FatalQuackers/pixlink/releases/latest"

    private val _available = MutableStateFlow<Update?>(null)
    /** The newer release, if one was found. The home screen shows a banner for it. */
    val available: StateFlow<Update?> = _available.asStateFlow()

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    /** Early updates: also take pre-releases, which everyone else gets a day later. */
    @Volatile var early: Boolean = false

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    /** Returns the newer release, or null if this is the latest. Throws on network errors. */
    suspend fun check(): Update? = withContext(Dispatchers.IO) {
        val json = if (early) {
            // The newest release of any kind (pre-releases included) that has an APK attached.
            Codec.parseToJsonElement(get("https://api.github.com/repos/FatalQuackers/pixlink/releases?per_page=10")).jsonArray
                .map { it.jsonObject }
                .filter { it["draft"]?.jsonPrimitive?.content != "true" }
                .filter { r -> r["assets"]?.jsonArray?.any { it.jsonObject["name"]?.jsonPrimitive?.content?.endsWith(".apk") == true } == true }
                .maxWithOrNull { a, b ->
                    val va = a["tag_name"]!!.jsonPrimitive.content.removePrefix("v")
                    val vb = b["tag_name"]!!.jsonPrimitive.content.removePrefix("v")
                    when { isNewer(va, vb) -> 1; isNewer(vb, va) -> -1; else -> 0 }
                } ?: return@withContext null.also { _available.value = null }
        } else Codec.parseToJsonElement(get(LATEST)).jsonObject

        val latest = json["tag_name"]!!.jsonPrimitive.content.removePrefix("v")
        val page = json["html_url"]!!.jsonPrimitive.content
        val apk = json["assets"]?.jsonArray
            ?.map { it.jsonObject }
            ?.firstOrNull { it["name"]?.jsonPrimitive?.content?.endsWith(".apk") == true }
            ?.get("browser_download_url")?.jsonPrimitive?.content ?: page

        // A rebuild of the same version (fixes shipped without a new number) has a higher build number.
        val build = BUILD_MARKER.find(json["body"]?.jsonPrimitive?.content ?: "")?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val sameVersionRebuild = !isNewer(currentVersion, latest) && !isNewer(latest, currentVersion) && build > BuildConfig.VERSION_CODE
        val update = if (isNewer(latest, currentVersion) || sameVersionRebuild) Update(latest, apk, page) else null
        _available.value = update
        update
    }

    /** Quiet check on app start; failures (offline, rate limit) are ignored. */
    suspend fun checkQuietly() {
        runCatching { check() }.onFailure { Log.d("UpdateChecker", "Check failed: ${it.message}") }
    }

    /** Compares dotted versions like 0.1.12 vs 0.1.9. Anything with "-dev" counts as older. */
    fun isNewer(latest: String, current: String): Boolean {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(latest); val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return current.contains("-dev")
    }
}
