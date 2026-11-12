package app.pixlink.pairing

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import app.pixlink.link.Codec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * Finds PCs that are showing a pairing code. While the pairing screen is open, the PC
 * broadcasts {"pairing":true,"name","fp","port"} on UDP 47102 every second.
 */
object PairingDiscovery {

    data class PcAd(val name: String, val host: String, val port: Int, val fingerprint: String, val pcId: String) {
        /** The same pixlink:// link the QR code holds, with the typed code as the token. */
        fun pairingUri(code: String): String = Uri.Builder()
            .scheme("pixlink").authority("pair")
            .appendQueryParameter("v", "1")
            .appendQueryParameter("host", host)
            .appendQueryParameter("port", port.toString())
            .appendQueryParameter("token", code)
            .appendQueryParameter("fp", "SHA256:$fingerprint")
            .appendQueryParameter("name", name)
            .build().toString()
    }

    private const val PORT = 47102

    /** Listens for up to [timeoutMs] and returns every PC with pairing open. Stops early once one is found + a short grace period. */
    suspend fun discover(context: Context, timeoutMs: Long = 8_000): List<PcAd> = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi.createMulticastLock("pixlink-pairing").apply { setReferenceCounted(false) }
        val found = linkedMapOf<String, PcAd>()
        lock.acquire()
        try {
            DatagramSocket(null).use { sock ->
                sock.reuseAddress = true
                sock.bind(InetSocketAddress(PORT))
                sock.soTimeout = 400
                val buf = ByteArray(1024)
                val start = System.currentTimeMillis()
                var firstFoundAt = 0L
                while (System.currentTimeMillis() - start < timeoutMs) {
                    // After the first PC appears, wait ~1.5 s more in case there are others.
                    if (firstFoundAt > 0 && System.currentTimeMillis() - firstFoundAt > 1_500) break
                    val packet = DatagramPacket(buf, buf.size)
                    try { sock.receive(packet) } catch (_: SocketTimeoutException) { continue }
                    val obj = runCatching {
                        Codec.parseToJsonElement(String(packet.data, 0, packet.length, Charsets.UTF_8)).jsonObject
                    }.getOrNull() ?: continue
                    if (obj["pairing"]?.jsonPrimitive?.booleanOrNull != true) continue
                    val pcId = obj["pcId"]?.jsonPrimitive?.content ?: continue
                    val ad = PcAd(
                        name = obj["name"]?.jsonPrimitive?.content ?: "PC",
                        host = packet.address.hostAddress ?: continue,
                        port = obj["port"]?.jsonPrimitive?.intOrNull ?: 47100,
                        fingerprint = obj["fp"]?.jsonPrimitive?.content ?: continue,
                        pcId = pcId,
                    )
                    if (found.put(pcId, ad) == null && firstFoundAt == 0L) firstFoundAt = System.currentTimeMillis()
                }
            }
        } finally {
            runCatching { lock.release() }
        }
        found.values.toList()
    }
}
