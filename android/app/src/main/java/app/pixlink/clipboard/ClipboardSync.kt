package app.pixlink.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import app.pixlink.link.LinkHub
import app.pixlink.link.msg
import app.pixlink.pairing.PairedStore
import kotlinx.serialization.json.put

/**
 * Clipboard between phone and PC.
 * - PC → phone: automatic (the PC sends what you copy; any app can write the clipboard).
 * - Phone → PC: Android only lets the app you're using read the clipboard, so it's sent when you
 *   open Pixlink, tap "Send clipboard" in its notification, or use the quick settings tile.
 * - Sign-in codes: picked out of incoming notifications and copied on the PC automatically.
 */
object ClipboardSync {
    @Volatile private var lastFromPc: String? = null
    @Volatile private var lastSent: String? = null

    fun fromPc(context: Context, text: String) {
        lastFromPc = text
        Handler(Looper.getMainLooper()).post {
            context.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("From your PC", text))
        }
    }

    /** Reads the clipboard (only works while a Pixlink screen is in front) and sends it to every connected PC. */
    fun sendCurrent(context: Context, quiet: Boolean = false): Boolean {
        val text = context.getSystemService(ClipboardManager::class.java).primaryClip
            ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        if (text.isNullOrEmpty()) return false
        if (quiet && (text == lastFromPc || text == lastSent)) return false
        lastSent = text
        LinkHub.send(msg("clipboard.text") { put("text", text); put("auto", quiet) })
        return true
    }

    // "Your code is 123456", "G-123456 is your Google verification code", "OTP: 4821"…
    private val keyword = Regex("""\b(code|otp|passcode|pin|verification|verify|one[- ]time|security|login|sign[- ]in|2fa|authenticat)""", RegexOption.IGNORE_CASE)
    private val candidate = Regex("""(?<![\d-])(?:[A-Z]-)?(\d{4,8}|\d{3}[- ]\d{3})(?![\d])""")

    fun findCode(text: String): String? {
        if (!keyword.containsMatchIn(text)) return null
        return candidate.findAll(text)
            .map { it.groupValues[1].replace(" ", "").replace("-", "") }
            .firstOrNull { it.length in 4..8 && !looksLikeYear(it) }
    }

    private fun looksLikeYear(s: String) = s.length == 4 && s.toInt() in 1990..2099

    /** Called for every new notification: if it carries a sign-in code, copy it on the PC. */
    fun maybeSendCode(context: Context, app: String, title: String, text: String) {
        if (!PairedStore(context).sendCodes) return
        val code = findCode("$title $text") ?: return
        LinkHub.send(msg("clipboard.code") { put("code", code); put("app", app) })
    }
}
