package app.pixlink.link

import android.content.Context
import android.util.Log
import app.pixlink.calls.CallBridge
import app.pixlink.features.PhoneRinger
import app.pixlink.files.FileBridge
import app.pixlink.media.PhotoBridge
import app.pixlink.mirror.MirrorService
import app.pixlink.mirror.ProjectionPermissionActivity
import app.pixlink.notifications.MediaBridge
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

/**
 * Routes each message from one PC to the feature that handles it. There's one router per
 * connection, so replies ([out]) go back to the PC that asked.
 */
class MessageRouter(
    private val context: Context,
    private val store: PairedStore,
    /** The PC this connection belongs to (null while pairing). */
    private val pcId: String?,
    private val out: (Envelope) -> Unit,
    private val onPaired: ((pcName: String, pcId: String) -> Unit)? = null,
    private val onRejected: ((reason: String) -> Unit)? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val photos by lazy { PhotoBridge(context) }
    private val files by lazy { FileBridge(context) }
    private val hotspot by lazy { HotspotController(context, store) }

    fun handle(m: Envelope) {
        // While pairing, only the pairing answer matters.
        if (pcId == null) {
            when (m.t) {
                MessageTypes.PAIR_ACCEPT -> onPaired?.invoke(m.str("pcName") ?: "PC", m.str("pcId") ?: "")
                MessageTypes.PAIR_REJECT -> onRejected?.invoke(m.str("reason") ?: "The PC said no")
            }
            return
        }

        // Input events are latency-sensitive and already coalesced by InputInjector.
        if (m.t.startsWith("input.")) { InputInjector.handle(context, m); return }

        // Incoming file data must be written in order, so handle it on the reading thread.
        if (m.t == "file.put" || m.t == "file.chunk") {
            runCatching { if (m.t == "file.put") files.beginReceive(m) else files.receiveChunk(m, out) }
                .onFailure { Log.w(TAG, "File receive failed", it) }
            return
        }

        scope.launch {
            try {
                route(m, pcId)
            } catch (e: Exception) {
                Log.w(TAG, "Handler for ${m.t} failed", e)
                m.id?.let { out(m.reply("error") { put("message", e.message ?: "error") }) }
            }
        }
    }

    private suspend fun route(m: Envelope, pcId: String) {
        when (m.t) {
            MessageTypes.PING -> out(msg(MessageTypes.PONG))

            // Notifications
            "notif.dismiss" -> NotificationBridge.dismiss(m.str("key")!!)
            "notif.reply" -> NotificationBridge.reply(m.str("key")!!, m.str("text")!!)
            "notif.action" -> NotificationBridge.action(m.str("key")!!, m.int("actionIndex")!!)
            "notif.sync" -> NotificationBridge.syncAll(out)

            // Mirroring (one PC at a time; a new request takes over)
            "mirror.start" -> ProjectionPermissionActivity.launch(
                context,
                pcId = pcId,
                maxSize = m.int("maxSize") ?: 1280,
                bitrate = m.int("bitrate") ?: 6_000_000,
                fps = m.int("fps") ?: 60,
            )
            "mirror.stop" -> MirrorService.stop(context)

            // Hotspot
            "hotspot.set" -> out(hotspot.set(m, m.bool("enabled") ?: true))
            "hotspot.get" -> hotspot.reportState()
            "hotspot.config" -> { hotspot.saveConfig(m); hotspot.reportState() }

            // Extras (each can be turned off in the phone's settings)
            "phone.ring" -> if (store.allowRing) {
                if (m.bool("ring") != false) PhoneRinger.start(context) else PhoneRinger.stop(context)
            }
            "dnd.set" -> if (store.allowDndControl) NotificationBridge.setDnd(m.bool("enabled") ?: false)
            "media.action" -> if (store.allowMediaControls) MediaBridge.action(m.str("action") ?: "toggle")
            "media.get" -> MediaBridge.report()
            "clipboard.set" -> if (store.allowClipboard) setClipboard(m.str("text") ?: "")

            // Calls
            "call.dial" -> calls.dial(m.str("number")!!)
            "call.answer" -> calls.answer()
            "call.hangup" -> calls.hangup()
            "contacts.list" -> out(m.reply("contacts.list") { put("contacts", calls.contacts(m.str("query"))) })
            "calllog.list" -> out(CallLogReader.list(m, m.int("limit") ?: 100))

            // Photos
            "photos.list" -> out(photos.list(m, m.int("offset") ?: 0, m.int("limit") ?: 200))
            "photos.thumb" -> out(photos.thumb(m, m.long("id")!!, m.int("size") ?: 256))

            // Files
            "files.list" -> out(files.list(m, m.str("path")))
            "files.roots" -> out(files.roots(m))
            "file.get" -> files.send(m, photos, out)

            else -> Log.d(TAG, "Unhandled message ${m.t}")
        }
    }

    private fun setClipboard(text: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val cm = context.getSystemService(android.content.ClipboardManager::class.java)
            cm.setPrimaryClip(android.content.ClipData.newPlainText("From your PC", text))
        }
    }

    companion object {
        private const val TAG = "Router"

        /** Call state is watched once for the whole app, not per connection. */
        @Volatile private var callBridge: CallBridge? = null
    }

    private val calls: CallBridge
        get() = callBridge ?: synchronized(MessageRouter::class.java) {
            callBridge ?: CallBridge(context.applicationContext).also { callBridge = it }
        }
}
