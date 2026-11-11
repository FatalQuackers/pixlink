package app.pixlink.power

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import app.pixlink.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * Power mode = Shizuku. Gives Pixlink shell-level access for features Android
 * reserves for system apps: screen control, hotspot toggle, call history, full storage.
 */
object PowerMode {

    enum class State { ShizukuNotInstalled, ShizukuNotRunning, NeedsPermission, Connecting, Ready }

    private val _state = MutableStateFlow(State.ShizukuNotRunning)
    val state: StateFlow<State> = _state.asStateFlow()
    val isReady get() = _state.value == State.Ready && shell != null

    @Volatile private var shell: IShellService? = null
    private lateinit var appContext: Context

    private const val PERMISSION_REQUEST = 42
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    private val serviceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(appContext.packageName, ShellService::class.java.name))
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            shell = binder?.takeIf { it.pingBinder() }?.let(IShellService.Stub::asInterface)
            refresh()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            shell = null
            refresh()
        }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        Shizuku.addBinderReceivedListenerSticky { refresh() }
        Shizuku.addBinderDeadListener { shell = null; refresh() }
        Shizuku.addRequestPermissionResultListener { code, result ->
            if (code == PERMISSION_REQUEST && result == PackageManager.PERMISSION_GRANTED) refresh()
        }
        refresh()
    }

    fun refresh() {
        _state.value = when {
            !isShizukuInstalled() -> State.ShizukuNotInstalled
            !Shizuku.pingBinder() -> State.ShizukuNotRunning
            Shizuku.isPreV11() -> State.ShizukuNotRunning
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> State.NeedsPermission
            shell == null -> { bind(); State.Connecting }
            else -> State.Ready
        }
    }

    fun requestPermission() {
        if (Shizuku.pingBinder()) Shizuku.requestPermission(PERMISSION_REQUEST)
    }

    private fun bind() {
        runCatching { Shizuku.bindUserService(serviceArgs, connection) }
    }

    private fun isShizukuInstalled() = runCatching {
        appContext.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0); true
    }.getOrDefault(false)

    /** Runs a shell command. Throws if Power mode is off. */
    suspend fun exec(command: String): String = withContext(Dispatchers.IO) {
        val s = shell ?: throw PowerModeRequired()
        s.exec(command)
    }

    suspend fun setWifiTethering(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        val s = shell ?: throw PowerModeRequired()
        s.setWifiTethering(enabled)
    }

    /** Quotes a value for safe use inside a shell command. */
    fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    class PowerModeRequired : Exception("power_mode_required")
}
