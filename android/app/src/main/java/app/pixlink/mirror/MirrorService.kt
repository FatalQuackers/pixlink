package app.pixlink.mirror

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import app.pixlink.PixlinkApp
import app.pixlink.R
import app.pixlink.link.LinkHub
import app.pixlink.link.msg
import app.pixlink.pairing.Identity
import app.pixlink.pairing.PairedStore
import kotlinx.serialization.json.put
import java.io.DataOutputStream
import java.net.InetSocketAddress
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread
import kotlin.math.max

/**
 * Captures the screen, encodes it as H.264, and streams it to the PC on port 47101.
 * Frame format: [uint32 length][uint64 pts µs][NAL bytes]. See docs/PROTOCOL.md.
 */
class MirrorService : Service() {

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var codec: MediaCodec? = null
    private var socket: SSLSocket? = null
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }

        // Android 14+: must be in the foreground with type mediaProjection BEFORE getMediaProjection().
        val notif = NotificationCompat.Builder(this, PixlinkApp.CHANNEL_MIRROR)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle("Sharing your screen with your PC")
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val resultCode = intent!!.getIntExtra(EXTRA_CODE, 0)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)!!
        val maxSize = intent.getIntExtra(EXTRA_MAX, 1280)
        val bitrate = intent.getIntExtra(EXTRA_BITRATE, 6_000_000)
        val fps = intent.getIntExtra(EXTRA_FPS, 60)

        val mpm = getSystemService(MediaProjectionManager::class.java)
        projection = mpm.getMediaProjection(resultCode, data).apply {
            registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, null)
        }

        thread(name = "pixlink-mirror") { stream(maxSize, bitrate, fps) }
        return START_NOT_STICKY
    }

    private fun stream(maxSize: Int, bitrate: Int, fps: Int) {
        val pc = PairedStore(this).pc ?: run { stopSelf(); return }
        try {
            // Work out the encode size: keep aspect ratio, longest side <= maxSize, multiples of 16.
            val bounds = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            val scale = minOf(1f, maxSize.toFloat() / max(bounds.width(), bounds.height()))
            val w = ((bounds.width() * scale).toInt() / 16) * 16
            val h = ((bounds.height() * scale).toInt() / 16) * 16
            val dpi = resources.displayMetrics.densityDpi

            val s = Identity.socketFactory(pc.fingerprint).createSocket() as SSLSocket
            socket = s
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(pc.host, MIRROR_PORT), 5_000)
            s.startHandshake()
            val out = DataOutputStream(s.outputStream.buffered(64 * 1024))

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000) // resend when screen is static
                setInteger(MediaFormat.KEY_LATENCY, 0)
                setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
            }
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec = enc
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = enc.createInputSurface()
            enc.start()

            display = projection!!.createVirtualDisplay(
                "pixlink", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null,
            )

            LinkHub.send(msg("mirror.started") { put("width", w); put("height", h) })

            running = true
            val info = MediaCodec.BufferInfo()
            while (running) {
                val index = enc.dequeueOutputBuffer(info, 100_000)
                if (index < 0) continue
                val buf = enc.getOutputBuffer(index)!!
                if (info.size > 0) {
                    val bytes = ByteArray(info.size)
                    buf.position(info.offset)
                    buf.get(bytes)
                    out.writeInt(bytes.size)
                    out.writeLong(info.presentationTimeUs)
                    out.write(bytes)
                    out.flush()
                }
                enc.releaseOutputBuffer(index, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        } catch (e: Exception) {
            Log.w(TAG, "Mirroring stopped: ${e.message}")
        } finally {
            LinkHub.send(msg("mirror.stop"))
            stopSelf()
        }
    }

    override fun onDestroy() {
        running = false
        runCatching { display?.release() }
        runCatching { codec?.stop(); codec?.release() }
        runCatching { projection?.stop() }
        runCatching { socket?.close() }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Mirror"
        private const val NOTIF_ID = 2
        const val MIRROR_PORT = 47101
        private const val ACTION_STOP = "stop"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        private const val EXTRA_MAX = "max"
        private const val EXTRA_BITRATE = "bitrate"
        private const val EXTRA_FPS = "fps"

        fun start(context: Context, code: Int, data: Intent, maxSize: Int, bitrate: Int, fps: Int) {
            context.startForegroundService(
                Intent(context, MirrorService::class.java)
                    .putExtra(EXTRA_CODE, code).putExtra(EXTRA_DATA, data)
                    .putExtra(EXTRA_MAX, maxSize).putExtra(EXTRA_BITRATE, bitrate).putExtra(EXTRA_FPS, fps)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MirrorService::class.java))
        }
    }
}
