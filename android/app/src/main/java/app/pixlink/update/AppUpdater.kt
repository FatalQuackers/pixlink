package app.pixlink.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import app.pixlink.pairing.PairedStore
import app.pixlink.power.PowerMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Installs new versions by itself.
 *  - Power mode on: `pm install` through Shizuku — fully silent.
 *  - Otherwise: Android's PackageInstaller with "no user action required". Android shows one
 *    confirmation the first time; after that Pixlink is its own installer and updates are silent
 *    (Android 12+).
 * Updates only install if they're signed with the same key as the installed app.
 */
object AppUpdater {

    sealed interface Status {
        data object Idle : Status
        data class Downloading(val version: String, val progress: Float) : Status
        data class Installing(val version: String) : Status
        /** The user needs to allow "Install unknown apps" for Pixlink once. */
        data object NeedsPermission : Status
        data class WaitingForConfirm(val version: String) : Status
        data class Failed(val message: String, val apkUrl: String?) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()
    private val lock = Mutex()

    internal fun setStatus(s: Status) { _status.value = s }

    /** Checks GitHub and, if there's a newer version and automatic updates are on, installs it. */
    suspend fun autoUpdate(context: Context) {
        if (!PairedStore(context).autoUpdate) { UpdateChecker.checkQuietly(); return }
        val update = runCatching { UpdateChecker.check() }.getOrNull() ?: return
        install(context, update)
    }

    suspend fun install(context: Context, update: UpdateChecker.Update) = lock.withLock {
        val app = context.applicationContext
        try {
            if (!PowerMode.isReady && !app.packageManager.canRequestPackageInstalls()) {
                _status.value = Status.NeedsPermission
                return@withLock
            }
            val apk = download(app, update)
            _status.value = Status.Installing(update.version)
            if (PowerMode.isReady) {
                // Silent install through Power mode; if Android refuses, use the normal installer instead.
                val silent = runCatching { installWithShell(app, apk) }
                if (silent.isFailure) {
                    Log.w("AppUpdater", "Power mode install failed, using the normal installer", silent.exceptionOrNull())
                    if (!app.packageManager.canRequestPackageInstalls()) { _status.value = Status.NeedsPermission; return@withLock }
                    installWithPackageInstaller(app, apk)
                }
            } else installWithPackageInstaller(app, apk)
        } catch (e: Exception) {
            Log.w("AppUpdater", "Update failed", e)
            _status.value = Status.Failed(friendly(e), update.apkUrl)
        }
    }

    /** A short message for the phone screen; the full details go to the log. */
    private fun friendly(e: Exception): String {
        val m = e.message.orEmpty()
        return when {
            "Unable to resolve host" in m || "timeout" in m.lowercase() -> "Couldn't download the update. Check your internet connection."
            "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in m -> "This update is signed differently from the installed app. Uninstall Tethr Link, then install the new version."
            "INSTALL_FAILED_INSUFFICIENT_STORAGE" in m -> "Not enough storage to install the update."
            m.length > 140 -> "Android couldn't install the update. Tap Download to install it yourself."
            else -> m.ifEmpty { "Update failed" }
        }
    }

    /** Downloads into Android/data/app.pixlink/files/updates (readable by the Power mode shell). */
    private suspend fun download(context: Context, update: UpdateChecker.Update): File = withContext(Dispatchers.IO) {
        val dir = File(context.getExternalFilesDir(null) ?: context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.filter { !it.name.contains(update.version) }?.forEach { it.delete() } // old downloads
        val file = File(dir, "pixlink-${update.version}.apk")
        val part = File(dir, "pixlink-${update.version}.apk.part")

        var url = URL(update.apkUrl)
        var conn: HttpURLConnection
        // GitHub redirects downloads to its file host; follow up to 5 hops.
        var hops = 0
        while (true) {
            conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            val code = conn.responseCode
            if (code in 300..399 && hops++ < 5) { url = URL(url, conn.getHeaderField("Location")); conn.disconnect(); continue }
            if (code != 200) throw IllegalStateException("Download failed (HTTP $code)")
            break
        }
        val total = conn.contentLengthLong
        if (file.exists() && file.length() == total) return@withContext file // already downloaded

        conn.inputStream.use { input ->
            part.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) _status.value = Status.Downloading(update.version, done.toFloat() / total)
                }
            }
        }
        part.renameTo(file)
        file
    }

    /**
     * The installer service can't read files in Android/data, so the shell first copies the APK to
     * /data/local/tmp (which it can), installs from there and cleans up.
     */
    private suspend fun installWithShell(context: Context, apk: File) {
        val tmp = "/data/local/tmp/pixlink-update.apk"
        val out = PowerMode.exec(
            "cp ${PowerMode.quote(apk.absolutePath)} $tmp && chmod 644 $tmp && " +
                "pm install -r -i ${context.packageName} $tmp; rm -f $tmp"
        )
        if (!out.contains("Success")) throw IllegalStateException(out.trim().ifEmpty { "pm install failed" })
        // Android stops the app to replace it; UpdateReceiver restarts the connection afterwards.
    }

    private fun installWithPackageInstaller(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("pixlink.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val callback = PendingIntent.getBroadcast(
                context, id,
                Intent(context, InstallResultReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(callback.intentSender)
        }
    }
}
