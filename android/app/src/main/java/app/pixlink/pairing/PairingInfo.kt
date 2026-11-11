package app.pixlink.pairing

import android.net.Uri

/**
 * Contents of the QR code the Windows app shows:
 * pixlink://pair?v=1&host=192.168.1.20&port=47100&token=...&fp=SHA256:AB:CD:...&name=DESKTOP-01
 */
data class PairingInfo(
    val host: String,
    val port: Int,
    val token: String,
    val fingerprint: String,
    val pcName: String,
) {
    companion object {
        fun parse(text: String): PairingInfo? {
            val uri = runCatching { Uri.parse(text.trim()) }.getOrNull() ?: return null
            if (uri.scheme != "pixlink" || uri.host != "pair") return null
            if (uri.getQueryParameter("v") != "1") return null
            return PairingInfo(
                host = uri.getQueryParameter("host") ?: return null,
                port = uri.getQueryParameter("port")?.toIntOrNull() ?: return null,
                token = uri.getQueryParameter("token") ?: return null,
                fingerprint = uri.getQueryParameter("fp")?.removePrefix("SHA256:")?.uppercase() ?: return null,
                pcName = uri.getQueryParameter("name") ?: "PC",
            )
        }
    }
}
