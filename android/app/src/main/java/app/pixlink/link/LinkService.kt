package app.pixlink.link

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.pixlink.BuildConfig
import app.pixlink.MainActivity
import app.pixlink.PixlinkApp
import app.pixlink.R
import app.pixlink.pairing.Identity
import app.pixlink.pairing.PairedPc
import app.pixlink.pairing.PairedStore
import app.pixlink.pairing.PairingInfo
import app.pixlink.power.PowerMode
import app.pixlink.status.StatusReporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.put

/**
 * Foreground service that keeps the link to the PC alive and reconnects with backoff.
 * Start with [start]; pass a [PairingInfo] to pair with a new PC.
 */
class LinkService : LifecycleService() {

    private lateinit var store: PairedStore
    private var loop: Job? = null
    private var pending: PairingInfo? = null
    private lateinit var router: MessageRouter
    private lateinit var status: StatusReporter

    override fun onCreate() {
        super.onCreate()
        store = PairedStore(this)
        router = MessageRouter(this, store, onPaired = ::onPaired, onRejected = ::onRejected)
        status = StatusReporter(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForeground(
            NOTIF_ID, buildNotification("Connecting…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )

        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_PAIR -> intent.getStringExtra(EXTRA_PAIR_URI)?.let { pending = PairingInfo.parse(it) }
        }

        if (pending == null && store.pc == null) {
            LinkHub.setState(LinkState.NotPaired)
            stopSelf()
            return START_NOT_STICKY
        }

        loop?.cancel()
        loop = lifecycleScope.launch(Dispatchers.IO) { connectLoop() }
        return START_STICKY
    }

    private suspend fun connectLoop() {
        var backoffMs = 1_000L
        while (lifecycleScope.isActive) {
            val pairing = pending
            val pc = store.pc
            val host = pairing?.host ?: pc?.host ?: return
            val port = pairing?.port ?: pc!!.port
            val fp = pairing?.fingerprint ?: pc!!.fingerprint
            val name = pairing?.pcName ?: pc!!.name

            LinkHub.setState(LinkState.Connecting(name))
            updateNotification("Connecting to $name…")

            val client = LinkClient(
                host, port, fp,
                onConnected = { c ->
                    backoffMs = 1_000L
                    if (pairing != null) {
                        c.send(msg(MessageTypes.PAIR_REQUEST) {
                            put("token", pairing.token)
                            put("phoneName", Build.MODEL)
                            put("model", Build.MODEL)
                            put("androidVersion", Build.VERSION.RELEASE)
                            put("phoneFingerprint", Identity.fingerprint)
                        })
                    } else {
                        sendHello(c, name)
                    }
                },
                onMessage = router::handle,
            )
            LinkHub.client = client
            try {
                client.run() // blocks until the connection ends
                LinkHub.lastDisconnectReason = client.closeReason ?: "Connection closed"
                Log.i(TAG, "Connection closed: ${LinkHub.lastDisconnectReason}")
            } catch (e: Exception) {
                LinkHub.lastDisconnectReason = when (e) {
                    is javax.net.ssl.SSLHandshakeException -> "Security check failed: ${e.message}"
                    is java.net.SocketTimeoutException -> "Couldn't reach $name at $host:$port. Same Wi-Fi? Firewall?"
                    is java.net.ConnectException -> "$name refused the connection. Is Pixlink open on the PC?"
                    else -> "${e.javaClass.simpleName}: ${e.message}"
                }
                Log.w(TAG, "Connection failed: ${LinkHub.lastDisconnectReason}", e)
                if (e is javax.net.ssl.SSLHandshakeException) {
                    LinkHub.setState(LinkState.Error("Security check failed — re-pair with your PC"))
                }
            } finally {
                client.close()
                if (LinkHub.client === client) LinkHub.client = null
                status.stop()
            }

            // A newer connect loop may have replaced this one; don't overwrite its state.
            if (!currentCoroutineContext().isActive) return
            if (LinkHub.state.value !is LinkState.Error) LinkHub.setState(LinkState.Disconnected)
            updateNotification("Waiting for ${name}…")
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(30_000)
        }
    }

    private fun sendHello(client: LinkClient, pcName: String) {
        client.send(msg(MessageTypes.HELLO) {
            put("phoneName", Build.MODEL)
            put("model", Build.MODEL)
            put("appVersion", BuildConfig.VERSION_NAME)
            put("powerMode", PowerMode.isReady)
        })
        LinkHub.setState(LinkState.Connected(pcName))
        updateNotification("Connected to $pcName")
        status.start()
    }

    private fun onPaired(pcName: String, pcId: String) {
        val p = pending ?: return
        store.pc = PairedPc(p.host, p.port, p.fingerprint, pcName, pcId)
        pending = null
        LinkHub.client?.let { sendHello(it, pcName) }
    }

    private fun onRejected(reason: String) {
        pending = null
        LinkHub.setState(LinkState.Error("PC rejected pairing: $reason"))
        LinkHub.client?.close()
        if (store.pc == null) stopSelf()
    }

    override fun onDestroy() {
        loop?.cancel()
        LinkHub.client?.close()
        LinkHub.client = null
        status.stop()
        if (store.pc == null) LinkHub.setState(LinkState.NotPaired) else LinkHub.setState(LinkState.Disconnected)
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, PixlinkApp.CHANNEL_LINK)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle("Pixlink")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(android.app.NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        private const val TAG = "LinkService"
        private const val NOTIF_ID = 1
        private const val ACTION_PAIR = "app.pixlink.PAIR"
        private const val ACTION_STOP = "app.pixlink.STOP"
        private const val EXTRA_PAIR_URI = "uri"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, LinkService::class.java))
        }

        fun pair(context: Context, uri: String) {
            context.startForegroundService(
                Intent(context, LinkService::class.java).setAction(ACTION_PAIR).putExtra(EXTRA_PAIR_URI, uri)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LinkService::class.java))
        }
    }
}
