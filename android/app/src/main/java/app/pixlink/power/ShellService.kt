package app.pixlink.power

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Lives in a separate process started by Shizuku, running as the shell user (uid 2000).
 * Only the commands in this file and in the power/ package are ever run.
 *
 * Shizuku 13+ passes a Context to this constructor.
 */
class ShellService(private val context: Context?) : IShellService.Stub() {

    constructor() : this(null)

    override fun destroy() {
        exitProcess(0)
    }

    override fun exec(command: String): String {
        val p = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(30, TimeUnit.SECONDS)
        return out
    }

    /**
     * Starts/stops Wi-Fi tethering through the hidden TetheringManager API.
     * The shell user holds TETHER_PRIVILEGED, so this works without root.
     * NOTE: hidden API — verify on each new Android release.
     */
    @SuppressLint("PrivateApi", "WrongConstant")
    override fun setWifiTethering(enabled: Boolean): Boolean {
        val ctx = context ?: return false
        return try {
            val tm = ctx.getSystemService("tethering") ?: return false
            val tmClass = tm.javaClass
            val typeWifi = 0 // TetheringManager.TETHERING_WIFI
            if (!enabled) {
                tmClass.getMethod("stopTethering", Int::class.javaPrimitiveType).invoke(tm, typeWifi)
                return true
            }
            val builderClass = Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
            val builder = builderClass.getConstructor(Int::class.javaPrimitiveType).newInstance(typeWifi)
            val request = builderClass.getMethod("build").invoke(builder)
            val callbackClass = Class.forName("android.net.TetheringManager\$StartTetheringCallback")
            val done = CountDownLatch(1)
            var ok = false
            val callback = Proxy.newProxyInstance(callbackClass.classLoader, arrayOf(callbackClass)) { _, method, args ->
                when (method.name) {
                    "onTetheringStarted" -> { ok = true; done.countDown() }
                    "onTetheringFailed" -> { Log.w("ShellService", "Tethering failed: ${args?.firstOrNull()}"); done.countDown() }
                }
                null
            }
            val executor = Executor { it.run() }
            tmClass.getMethod("startTethering", request.javaClass, Executor::class.java, callbackClass)
                .invoke(tm, request, executor, callback)
            done.await(10, TimeUnit.SECONDS)
            ok
        } catch (e: Throwable) {
            Log.w("ShellService", "setWifiTethering failed", e)
            false
        }
    }
}
