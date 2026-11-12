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
import app.pixlink.update.AppUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/**
 * Foreground service that keeps a link to every paired PC alive (work laptop, home PC…),
 * each reconnecting on its own with backoff. Pairing a new PC runs as a separate,
 * short-lived connection.
 */
class LinkService : LifecycleService() {

    private lateinit var store: PairedStore
    private lateinit var status: StatusReporter
    private val loops = ConcurrentHashMap<String, Job>()
    /** Wakes a PC's reconnect loop early, e.g. when it announces a new address. */
    private val wakes = ConcurrentHashMap<String, Channel<Unit>>()
    private var pairJob: Job? = null
    @Volatile private var beaconSocket: java.net.DatagramSocket? = null

    override fun onCreate() {
        super.onCreate()
        store = PairedStore(this)
        status = StatusReporter(this)

        lifecycleScope.launch(Dispatchers.IO) { listenForPcs() }

        // Automatic updates: check now and every 6 hours while Pixlink is running.
        lifecycleScope.launch(Dispatchers.IO) {
            delay(10_000)
            while (true) {
                runCatching { AppUpdater.autoUpdate(this@LinkService) }
                delay(6 * 60 * 60 * 1000L)
            }
        }

        // Tell the PCs whenever Power mode turns on or off, so screen control follows along.
        lifecycleScope.launch {
            PowerMode.state.collect { st ->
                LinkHub.send(msg("power.state") {
                    put("enabled", st == PowerMode.State.Ready)
                    put("canControl", st == PowerMode.State.Ready || app.pixlink.mirror.ControlService.isEnabled(this@LinkService))
                })
            }
        }

        // Keep the status-bar notification in step with the connections.
        lifecycleScope.launch {
            LinkHub.states.collect { refreshNotification() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForeground(NOTIF_ID, buildNotification("Connecting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)

        when (intent?.action) {
            ACTION_PAIR -> intent.getStringExtra(EXTRA_PAIR_URI)?.let(PairingInfo::parse)?.let(::startPairing)
        }
        syncLoops()
        stopIfIdle()
        return START_STICKY
    }

    /** Starts a connection for every paired PC that doesn't have one; stops forgotten ones. */
    private fun syncLoops() {
        val ids = store.pcs.map { it.id }.toSet()
        for (id in ids) {
            if (loops[id]?.isActive != true) loops[id] = lifecycleScope.launch(Dispatchers.IO) { connectLoop(id) }
        }
        for ((id, job) in loops.entries.toList()) {
            if (id !in ids) {
                job.cancel()
                LinkHub.client(id)?.close()
                loops.remove(id)
                wakes.remove(id)
                LinkHub.clearState(id)
                LinkHub.lastDisconnectReason.remove(id)
            }
        }
    }

    private fun stopIfIdle() {
        if (store.pcs.isEmpty() && pairJob?.isActive != true) stopSelf()
    }

    // ---------- Known PCs ----------

    private suspend fun connectLoop(pcId: String) {
        var backoffMs = 1_000L
        val wake = wakes.getOrPut(pcId) { Channel(Channel.CONFLATED) }
        while (currentCoroutineContext().isActive) {
            val pc = store.pc(pcId) ?: return
            LinkHub.setState(pcId, LinkState.Connecting(pc.name))

            lateinit var client: LinkClient
            client = LinkClient(
                pc.host, pc.port, pc.fingerprint,
                onConnected = { c ->
                    backoffMs = 1_000L
                    LinkHub.attach(pcId, c)
                    sendHello(c, pcId, pc.name)
                },
                onMessage = MessageRouter(this, store, pcId, out = { client.send(it) })::handle,
            )
            try {
                client.run() // blocks until the connection ends
                LinkHub.lastDisconnectReason[pcId] = client.closeReason ?: "Connection closed"
            } catch (e: Exception) {
                LinkHub.lastDisconnectReason[pcId] = describe(e, pc.name, pc.host, pc.port)
                Log.w(TAG, "Connection to ${pc.name} failed: ${LinkHub.lastDisconnectReason[pcId]}")
                if (e is javax.net.ssl.SSLHandshakeException) {
                    LinkHub.setState(pcId, LinkState.Error("${pc.name} no longer recognises your phone. Pair again."))
                }
            } finally {
                client.close()
                LinkHub.detach(pcId, client)
                if (!LinkHub.isConnected) status.stop()
            }

            if (!currentCoroutineContext().isActive) return
            if (LinkHub.states.value[pcId] !is LinkState.Error) LinkHub.setState(pcId, LinkState.Disconnected)
            withTimeoutOrNull(backoffMs) { wake.receive() }
            backoffMs = (backoffMs * 2).coerceAtMost(30_000)
        }
    }

    private fun sendHello(client: LinkClient, pcId: String, pcName: String) {
        client.send(msg(MessageTypes.HELLO) {
            put("phoneName", store.displayName)
            put("model", Build.MODEL)
            put("appVersion", BuildConfig.VERSION_NAME)
            put("powerMode", PowerMode.isReady)
            put("canControl", PowerMode.isReady || app.pixlink.mirror.ControlService.isEnabled(this@LinkService))
        })
        LinkHub.setState(pcId, LinkState.Connected(pcName))
        status.start()
        status.resend() // the newly connected PC gets battery etc. straight away
    }

    // ---------- Pairing a new PC ----------

    /**
     * Connects once to a PC showing a pairing code. On success the PC is saved; this pairing
     * connection then closes and the PC's normal connection takes over a moment later.
     */
    private fun startPairing(info: PairingInfo) {
        pairJob?.cancel()
        LinkHub.setPairing(LinkState.Connecting(info.pcName))
        pairJob = lifecycleScope.launch(Dispatchers.IO) {
            lateinit var client: LinkClient
            var paired: PairedPc? = null
            client = LinkClient(
                info.host, info.port, info.fingerprint,
                onConnected = { c ->
                    c.send(msg(MessageTypes.PAIR_REQUEST) {
                        put("token", info.token)
                        put("phoneName", store.displayName)
                        put("model", Build.MODEL)
                        put("androidVersion", Build.VERSION.RELEASE)
                        put("phoneFingerprint", Identity.fingerprint)
                    })
                },
                onMessage = MessageRouter(
                    this@LinkService, store, pcId = null, out = { client.send(it) },
                    onPaired = { name, id ->
                        val pc = PairedPc(info.host, info.port, info.fingerprint, name, id.ifEmpty { info.fingerprint })
                        store.add(pc)
                        paired = pc
                        LinkHub.setPairing(LinkState.Connected(name))
                        client.close() // the normal connection takes over
                    },
                    onRejected = { reason ->
                        LinkHub.setPairing(LinkState.Error(reason))
                        client.close()
                    },
                )::handle,
            )
            try {
                client.run()
            } catch (e: Exception) {
                if (paired == null && LinkHub.pairing.value !is LinkState.Error) {
                    LinkHub.setPairing(LinkState.Error(describe(e, info.pcName, info.host, info.port)))
                }
            } finally {
                client.close()
            }
            if (paired == null && LinkHub.pairing.value is LinkState.Connecting) {
                LinkHub.setPairing(LinkState.Error("${info.pcName} closed the connection. Show a new code and try again."))
            }
            launch(Dispatchers.Main) { syncLoops(); stopIfIdle() }
        }
    }

    private fun describe(e: Exception, name: String, host: String, port: Int) = when (e) {
        is javax.net.ssl.SSLHandshakeException -> "Security check failed with $name"
        is java.net.SocketTimeoutException -> "Couldn't reach $name at $host:$port. Same Wi-Fi? Firewall?"
        is java.net.ConnectException -> "$name refused the connection. Is Pixlink open on the PC?"
        else -> "${e.javaClass.simpleName}: ${e.message}"
    }

    // ---------- Finding PCs that moved ----------

    /**
     * Each PC broadcasts "I'm here" on UDP 47102 every few seconds. If a paired PC shows up
     * at a new address (new Wi-Fi, or it joined this phone's hotspot), switch to it and reconnect.
     */
    private suspend fun listenForPcs() {
        val wifi = applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
        val lock = wifi.createMulticastLock("pixlink-beacon").apply { setReferenceCounted(false) }
        try {
            lock.acquire()
            java.net.DatagramSocket(null).use { sock ->
                beaconSocket = sock
                sock.reuseAddress = true
                sock.bind(java.net.InetSocketAddress(BEACON_PORT))
                val buf = ByteArray(1024)
                while (currentCoroutineContext().isActive) {
                    val packet = java.net.DatagramPacket(buf, buf.size)
                    sock.receive(packet)
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val obj = runCatching { Codec.parseToJsonElement(text).jsonObject }.getOrNull() ?: continue
                    val id = obj["pcId"]?.jsonPrimitive?.content ?: continue
                    val pc = store.pc(id) ?: continue
                    val host = packet.address.hostAddress ?: continue
                    if (host != pc.host && LinkHub.states.value[id] !is LinkState.Connected) {
                        Log.i(TAG, "${pc.name} moved to $host")
                        store.updateHost(id, host)
                        wakes[id]?.trySend(Unit)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Beacon listener stopped: ${e.message}")
        } finally {
            runCatching { lock.release() }
        }
    }

    override fun onDestroy() {
        runCatching { beaconSocket?.close() } // unblocks the beacon listener
        loops.values.forEach { it.cancel() }
        pairJob?.cancel()
        for (pc in store.pcs) {
            LinkHub.client(pc.id)?.close()
            if (LinkHub.states.value[pc.id] !is LinkState.Error) LinkHub.setState(pc.id, LinkState.Disconnected)
        }
        status.stop()
        super.onDestroy()
    }

    // ---------- Status bar notification ----------

    private fun refreshNotification() {
        val connected = LinkHub.states.value.values.filterIsInstance<LinkState.Connected>().map { it.pcName }
        // No "searching…" noise: while nothing is connected the notification just says Pixlink is ready.
        val text = if (connected.isNotEmpty()) "Connected to ${connected.joinToString()}" else "Ready. Connects when your PC is nearby."
        getSystemService(android.app.NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text, connected.isNotEmpty()))
    }

    private fun buildNotification(text: String, connected: Boolean = false): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(this, PixlinkApp.CHANNEL_LINK)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle(if (connected) "Pixlink" else "Pixlink is ready")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setShowWhen(false)
        if (connected) {
            val send = PendingIntent.getActivity(this, 3,
                Intent(this, app.pixlink.clipboard.ClipboardSendActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE)
            b.addAction(0, "Send clipboard", send)
        }
        return b.build()
    }

    companion object {
        private const val TAG = "LinkService"
        private const val NOTIF_ID = 1
        private const val BEACON_PORT = 47102
        private const val ACTION_PAIR = "app.pixlink.PAIR"
        private const val EXTRA_PAIR_URI = "uri"

        /** Starts (or refreshes) connections to every paired PC. */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, LinkService::class.java))
        }

        /** Pairs with a new PC (from a QR code, a typed code or a pasted link). */
        fun pair(context: Context, uri: String) {
            LinkHub.setPairing(LinkState.Connecting(PairingInfo.parse(uri)?.pcName ?: "your PC"))
            context.startForegroundService(
                Intent(context, LinkService::class.java).setAction(ACTION_PAIR).putExtra(EXTRA_PAIR_URI, uri)
            )
        }

        /** Forgets one PC and drops its connection. */
        fun forget(context: Context, pcId: String) {
            PairedStore(context).remove(pcId)
            LinkHub.clearState(pcId)
            context.startForegroundService(Intent(context, LinkService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LinkService::class.java))
        }
    }
}
