package app.pixlink.messages

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import app.pixlink.link.Envelope
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Your text history for the PC's Messages tab, read from the phone itself, so there's no
 * Google Messages sign-in. RCS chats aren't stored where apps can read them; those still
 * arrive on the PC through notifications.
 */
object SmsBridge {
    private fun allowed(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private var observer: android.database.ContentObserver? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val notify = Runnable { app.pixlink.link.LinkHub.send(app.pixlink.link.msg("sms.changed")) }

    /** Tells the PCs whenever a text arrives or is sent, so their Messages tab updates live. */
    fun startWatching(ctx: Context) {
        if (observer != null || !allowed(ctx, Manifest.permission.READ_SMS)) return
        val o = object : android.database.ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                handler.removeCallbacks(notify)
                handler.postDelayed(notify, 700) // a text often changes several rows at once
            }
        }
        runCatching { ctx.contentResolver.registerContentObserver(Uri.parse("content://sms"), true, o) }
            .onSuccess { observer = o }
    }

    fun stopWatching(ctx: Context) {
        observer?.let { runCatching { ctx.contentResolver.unregisterContentObserver(it) } }
        observer = null
    }

    fun threads(ctx: Context, request: Envelope, limit: Int): Envelope {
        if (!allowed(ctx, Manifest.permission.READ_SMS)) return request.reply("sms.threads") { put("error", "permission") }
        data class T(val id: Long, val address: String, val body: String, val date: Long)
        val latest = LinkedHashMap<Long, T>()
        ctx.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            null, null, "${Telephony.Sms.DATE} DESC",
        )?.use { c ->
            var rows = 0
            while (c.moveToNext() && latest.size < limit && rows++ < 5000) {
                val id = c.getLong(0)
                if (id !in latest) latest[id] = T(id, c.getString(1) ?: "", c.getString(2) ?: "", c.getLong(3))
            }
        }
        val names = HashMap<String, String?>()
        return request.reply("sms.threads") {
            putJsonArray("threads") {
                latest.values.forEach { t ->
                    addJsonObject {
                        put("threadId", t.id)
                        put("address", t.address)
                        put("name", names.getOrPut(t.address) { contactName(ctx, t.address) } ?: t.address)
                        put("snippet", t.body.take(140))
                        put("date", t.date)
                    }
                }
            }
        }
    }

    fun thread(ctx: Context, request: Envelope, threadId: Long, limit: Int): Envelope {
        if (!allowed(ctx, Manifest.permission.READ_SMS)) return request.reply("sms.thread") { put("error", "permission") }
        return request.reply("sms.thread") {
            put("threadId", threadId)
            putJsonArray("messages") {
                ctx.contentResolver.query(
                    Telephony.Sms.CONTENT_URI,
                    arrayOf(Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
                    "${Telephony.Sms.THREAD_ID} = ?", arrayOf(threadId.toString()), "${Telephony.Sms.DATE} DESC",
                )?.use { c ->
                    var n = 0
                    while (c.moveToNext() && n++ < limit) addJsonObject {
                        put("body", c.getString(0) ?: "")
                        put("date", c.getLong(1))
                        put("fromMe", c.getInt(2) != Telephony.Sms.MESSAGE_TYPE_INBOX)
                    }
                }
            }
        }
    }

    fun send(ctx: Context, request: Envelope, address: String, text: String): Envelope {
        if (!allowed(ctx, Manifest.permission.SEND_SMS)) return request.reply("sms.send") { put("ok", false); put("error", "permission") }
        return try {
            val sms = ctx.getSystemService(SmsManager::class.java)
            val parts = sms.divideMessage(text)
            if (parts.size > 1) sms.sendMultipartTextMessage(address, null, parts, null, null)
            else sms.sendTextMessage(address, null, text, null, null)
            request.reply("sms.send") { put("ok", true) }
        } catch (e: Exception) {
            request.reply("sms.send") { put("ok", false); put("error", e.message ?: "failed") }
        }
    }

    private fun contactName(ctx: Context, number: String): String? {
        if (number.isBlank() || !allowed(ctx, Manifest.permission.READ_CONTACTS)) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return runCatching {
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }
}
