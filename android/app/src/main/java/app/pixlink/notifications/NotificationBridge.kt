package app.pixlink.notifications

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import androidx.core.graphics.drawable.toBitmap
import app.pixlink.link.LinkHub
import app.pixlink.link.msg
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.ByteArrayOutputStream

/**
 * Mirrors notifications to the PC and lets the PC reply, dismiss or tap actions.
 * Replies use the notification's own reply action, so texting works for SMS, RCS
 * (Google Messages) and chat apps — without the restricted SMS permission.
 */
class NotificationBridge : NotificationListenerService() {

    private val iconsSent = mutableSetOf<String>()

    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!shouldMirror(sbn)) return
        sendIconOnce(sbn.packageName)
        LinkHub.send(toMessage(sbn))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        LinkHub.send(msg("notif.removed") { put("key", sbn.key) })
    }

    private fun shouldMirror(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        if (sbn.packageName == packageName) return false
        if (sbn.isOngoing && n.category != Notification.CATEGORY_CALL) return false
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        return true
    }

    private fun toMessage(sbn: StatusBarNotification) = msg("notif.posted") {
        val n = sbn.notification
        val extras = n.extras
        val appName = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)

        put("key", sbn.key)
        put("app", sbn.packageName)
        put("appName", appName)
        put("title", extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "")
        put("text", (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: "")
        put("time", sbn.postTime)
        put("canReply", findReplyAction(n) != null)
        put("isMessage", n.category == Notification.CATEGORY_MESSAGE)
        extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.let { put("conversation", it.toString()) }
        putJsonArray("actions") {
            n.actions?.filter { it.remoteInputs.isNullOrEmpty() }?.forEach { add(it.title.toString()) }
        }
    }

    private fun sendIconOnce(pkg: String) {
        if (!iconsSent.add(pkg)) return
        val icon = runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull() ?: return
        val bmp = icon.toBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        LinkHub.send(msg("app.icon") {
            put("app", pkg)
            put("pngBase64", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
        })
    }

    companion object {
        @Volatile private var instance: NotificationBridge? = null

        val isEnabled get() = instance != null

        private fun findReplyAction(n: Notification): Notification.Action? =
            n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }

        private fun find(key: String): StatusBarNotification? =
            instance?.activeNotifications?.firstOrNull { it.key == key }

        fun syncAll() {
            val svc = instance ?: return
            svc.activeNotifications?.filter(svc::shouldMirror)?.forEach {
                svc.sendIconOnce(it.packageName)
                LinkHub.send(svc.toMessage(it))
            }
        }

        fun dismiss(key: String) {
            instance?.cancelNotification(key)
        }

        /** Fills the notification's reply box with [text] and sends it. */
        fun reply(key: String, text: String) {
            val svc = instance ?: return
            val sbn = find(key) ?: return
            val action = findReplyAction(sbn.notification) ?: return
            val intent = Intent()
            val results = Bundle()
            action.remoteInputs.forEach { results.putCharSequence(it.resultKey, text) }
            RemoteInput.addResultsToIntent(action.remoteInputs, intent, results)
            action.actionIntent.send(svc, 0, intent)
        }

        /** Taps the notification's Nth non-reply action button. */
        fun action(key: String, index: Int) {
            val svc = instance ?: return
            val sbn = find(key) ?: return
            val actions = sbn.notification.actions?.filter { it.remoteInputs.isNullOrEmpty() } ?: return
            actions.getOrNull(index)?.actionIntent?.send(svc, 0, null)
        }
    }
}
