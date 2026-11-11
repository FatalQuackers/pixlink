package app.pixlink.link

import android.util.Log
import app.pixlink.pairing.Identity
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import javax.net.ssl.SSLSocket

/**
 * One TLS connection to the PC's control port. Reads newline-delimited JSON and
 * hands each message to [onMessage]. Blocking — run [run] on a background thread.
 */
class LinkClient(
    private val host: String,
    private val port: Int,
    private val pcFingerprint: String,
    private val onConnected: (LinkClient) -> Unit,
    private val onMessage: (Envelope) -> Unit,
) {
    @Volatile private var socket: SSLSocket? = null
    @Volatile private var writer: BufferedWriter? = null
    private val writeLock = Any()

    /** Connects and blocks reading until the connection closes or fails. */
    fun run() {
        val factory = Identity.socketFactory(pcFingerprint)
        val s = factory.createSocket() as SSLSocket
        socket = s
        s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        s.soTimeout = READ_TIMEOUT_MS
        s.startHandshake()

        writer = BufferedWriter(OutputStreamWriter(s.outputStream, Charsets.UTF_8))
        val reader = BufferedReader(InputStreamReader(s.inputStream, Charsets.UTF_8))
        onConnected(this)

        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            val env = runCatching { Codec.decodeFromString(Envelope.serializer(), line) }
                .onFailure { Log.w(TAG, "Bad message: ${line.take(200)}", it) }
                .getOrNull() ?: continue
            onMessage(env)
        }
    }

    fun send(env: Envelope) {
        val w = writer ?: return
        val line = Codec.encodeToString(Envelope.serializer(), env)
        synchronized(writeLock) {
            runCatching {
                w.write(line)
                w.write("\n")
                w.flush()
            }.onFailure { Log.w(TAG, "Send failed", it); close() }
        }
    }

    fun close() {
        runCatching { socket?.close() }
        socket = null
        writer = null
    }

    companion object {
        private const val TAG = "LinkClient"
        private const val CONNECT_TIMEOUT_MS = 5_000
        /** PC pings every 15 s, so 45 s of silence means the link is dead. */
        private const val READ_TIMEOUT_MS = 45_000
    }
}
