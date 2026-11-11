package app.pixlink.link

import android.content.Context
import android.util.Log
import app.pixlink.calls.CallBridge
import app.pixlink.files.FileBridge
import app.pixlink.media.PhotoBridge
import app.pixlink.mirror.MirrorService
import app.pixlink.mirror.ProjectionPermissionActivity
import app.pixlink.notifications.NotificationBridge
import app.pixlink.pairing.PairedStore
import app.pixlink.power.CallLogReader
import app.pixlink.power.HotspotController
import app.pixlink.power.InputInjector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.put

/** Routes each incoming message to the feature that handles it. */
class MessageRouter(
    private val context: Context,
    private val store: PairedStore,
    private val onPaired: (pcName: String, pcId: String) -> Unit,
    private val onRejected: (reason: String) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val calls by lazy { CallBridge(context) }
    private val photos by lazy { PhotoBridge(context) }
    private val files by lazy { FileBridge(context) }
    private val hotspot by lazy { HotspotController(context, store) }

    fun handle(m: Envelope) {
        // Input events are latency-sensitive and already coalesced by InputInjector.
        if (m.t.startsWith("input.")) { InputInjector.handle(context, m); return }

        // Incoming file data must be written in order, so handle it on the reading thread.
        if (m.t == "file.put" || m.t == "file.chunk") {
            runCatching { if (m.t == "file.put") files.beginReceive(m) else files.receiveChunk(m) }
                .onFailure { Log.w(TAG, "File receive failed", it) }
            return
        }

        scope.launch {
            try {
                route(m)
            } catch (e: Exception) {
                Log.w(TAG, "Handler for ${m.t} failed", e)
                m.id?.let { LinkHub.send(m.reply("error") { put("message", e.message ?: "error") }) }
            }
        }
    }

    private suspend fun route(m: Envelope) {
        when (m.t) {
            MessageTypes.PING -> LinkHub.send(msg(MessageTypes.PONG))
            MessageTypes.PAIR_ACCEPT -> onPaired(m.str("pcName") ?: "PC", m.str("pcId") ?: "")
            MessageTypes.PAIR_REJECT -> onRejected(m.str("reason") ?: "unknown")

            // Notifications
            "notif.dismiss" -> NotificationBridge.dismiss(m.str("key")!!)
            "notif.reply" -> NotificationBridge.reply(m.str("key")!!, m.str("text")!!)
            "notif.action" -> NotificationBridge.action(m.str("key")!!, m.int("actionIndex")!!)
            "notif.sync" -> NotificationBridge.syncAll()

            // Mirroring
            "mirror.start" -> ProjectionPermissionActivity.launch(
                context,
                maxSize = m.int("maxSize") ?: 1280,
                bitrate = m.int("bitrate") ?: 6_000_000,
                fps = m.int("fps") ?: 60,
            )
            "mirror.stop" -> MirrorService.stop(context)

            // Hotspot
            "hotspot.set" -> hotspot.set(m.bool("enabled") ?: true)
            "hotspot.get" -> hotspot.reportState()

            // Calls
            "call.dial" -> calls.dial(m.str("number")!!)
            "call.answer" -> calls.answer()
            "call.hangup" -> calls.hangup()
            "contacts.list" -> LinkHub.send(m.reply("contacts.list") { put("contacts", calls.contacts(m.str("query"))) })
            "calllog.list" -> LinkHub.send(CallLogReader.list(m, m.int("limit") ?: 100))

            // Photos
            "photos.list" -> LinkHub.send(photos.list(m, m.int("offset") ?: 0, m.int("limit") ?: 200))
            "photos.thumb" -> LinkHub.send(photos.thumb(m, m.long("id")!!, m.int("size") ?: 256))

            // Files
            "files.list" -> LinkHub.send(files.list(m, m.str("path")))
            "files.roots" -> LinkHub.send(files.roots(m))
            "file.get" -> files.send(m, photos)

            else -> Log.d(TAG, "Unhandled message ${m.t}")
        }
    }

    companion object { private const val TAG = "Router" }
}
