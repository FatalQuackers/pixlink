package app.pixlink.status

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import app.pixlink.link.LinkHub
import app.pixlink.link.msg
import kotlinx.serialization.json.put

/** Sends battery, charging and Do Not Disturb state to the PC (shown in the phone panel). */
class StatusReporter(private val context: Context) {
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) = send(intent)
    }

    fun start() {
        if (registered) return
        val sticky = context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        registered = true
        sticky?.let(::send)
    }

    /** Sends the current battery state again (e.g. to a PC that just connected). */
    fun resend() {
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let(::send)
    }

    fun stop() {
        if (!registered) return
        runCatching { context.unregisterReceiver(receiver) }
        registered = false
    }

    private fun send(battery: Intent) {
        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val nm = context.getSystemService(NotificationManager::class.java)
        LinkHub.send(msg("status") {
            put("battery", if (level >= 0) level * 100 / scale else -1)
            put("charging", plugged)
            put("dnd", nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL)
        })
    }
}
