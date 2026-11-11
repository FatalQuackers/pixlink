package app.pixlink.link

import android.util.Log
import app.pixlink.pairing.Identity
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/**
 * One TLS connection to the PC's control port. Reads newline-delimited JSON and
 * hands each message to [onMessage]. Blocking — run [run] on a background thread.
 *
 * Sending never touches the network on the caller's thread: messages go into a queue
 * that a dedicated writer thread drains. (Android forbids network I/O on the main
 * thread, and notifications, battery updates and call events all arrive there.)
 */
class LinkClient(
    private val host: String,
    private val port: Int,
    private val pcFingerprint: String,
    private val onConnected: (LinkClient) -> Unit,
    private val onMessage: (Envelope) -> Unit,
) {
    @Volatile private var socket: SSLSocket? = null
    @Volatile private var closed = false
    private val outbox = LinkedBlockingQueue<String>(2_000)

    /** Why the connection ended, for the UI. */
    @Volatile var closeReason: String? = null
        private set

    /** Connects and blocks reading until the connection closes or fails. */
    fun run() {
        val factory = Identity.socketFactory(pcFingerprint)
        val s = factory.createSocket() as SSLSocket
        socket = s
        s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        s.soTimeout = READ_TIMEOUT_MS
        s.startHandshake()

        val writer = BufferedWriter(OutputStreamWriter(s.outputStream, Charsets.UTF_8))
        thread(name = "pixlink-writer", isDaemon = true) { writeLoop(writer) }

        val reader = BufferedReader(InputStreamReader(s.inputStream, Charsets.UTF_8))
        onConnected(this)

        while (!closed) {
            val line = reader.readLine()
            if (line == null) {
                closeReason = closeReason ?: "PC closed the connection"
                break
            }
            if (line.isBlank()) continue
            val env = runCatching { Codec.decodeFromString(Envelope.serializer(), line) }
                .onFailure { Log.w(TAG, "Bad message: ${line.take(200)}", it) }
                .getOrNull() ?: continue
            onMessage(env)
        }
    }

    private fun writeLoop(writer: BufferedWriter) {
        try {
            while (!closed) {
                val line = outbox.poll(1, TimeUnit.SECONDS) ?: continue
                writer.write(line)
                writer.write("\n")
                // Batch whatever else is waiting before flushing.
                while (true) {
                    val next = outbox.poll() ?: break
                    writer.write(next)
                    writer.write("\n")
                }
                writer.flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Write failed", e)
            closeReason = closeReason ?: "Couldn't send to PC: ${e.message}"
            close()
        }
    }

    /** Queues a message. Safe to call from any thread, including the main thread. */
    fun send(env: Envelope) {
        if (closed) return
        val line = Codec.encodeToString(Envelope.serializer(), env)
        if (!outbox.offer(line)) Log.w(TAG, "Outbox full, dropping ${env.t}")
    }

    fun close() {
        closed = true
        runCatching { socket?.close() }
        socket = null
    }

    companion object {
        private const val TAG = "LinkClient"
        private const val CONNECT_TIMEOUT_MS = 5_000
        /** PC pings every 15 s, so 45 s of silence means the link is dead. */
        private const val READ_TIMEOUT_MS = 45_000
    }
}
