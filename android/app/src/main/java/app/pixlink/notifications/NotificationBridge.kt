package app.pixlink.notifications

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.toBitmap
import app.pixlink.link.Envelope
import app.pixlink.link.LinkHub
import app.pixlink.link.msg
import app.pixlink.pairing.PairedStore
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.ByteArrayOutputStream

/**
 * Mirrors notifications to the PCs and lets a PC reply, dismiss or press the app's own buttons
 * (Mark as read, Like…). Replies use the notification's own reply action, so texting works for
 * SMS, RCS (Google Messages) and chat apps — without the restricted SMS permission.
 */
class NotificationBridge : NotificationListenerService() {

    private val iconsSent = mutableSetOf<String>()
    private val store by lazy { PairedStore(this) }

    override fun onListenerConnected() {
        instance = this
        MediaBridge.start(this)
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        MediaBridge.stop()
    }

    override fun onInterruptionFilterChanged(interruptionFilter: Int) {
        LinkHub.send(msg("dnd.state") { put("enabled", interruptionFilter != INTERRUPTION_FILTER_ALL) })
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!shouldMirror(sbn)) return
        sendIconOnce(sbn.packageName, LinkHub::send)
        LinkHub.send(toMessage(sbn))
        // A sign-in code in a new notification goes straight onto the PC's clipboard.
        if (sbn.notification.`when` > System.currentTimeMillis() - 120_000) {
            val e = sbn.notification.extras
            app.pixlink.clipboard.ClipboardSync.maybeSendCode(this, appName(sbn.packageName),
                e.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ?: "",
                (e.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(android.app.Notification.EXTRA_TEXT))?.toString() ?: "")
        }
    }

    private fun appName(pkg: String) = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        LinkHub.send(msg("notif.removed") { put("key", sbn.key) })
    }

    private fun shouldMirror(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        if (sbn.packageName == packageName) return false
        if (!store.mirrorNotifications) return false
        if (sbn.packageName in store.blockedApps) return false
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
        val hide = store.hideContent && n.visibility != Notification.VISIBILITY_PUBLIC

        put("key", sbn.key)
        put("app", sbn.packageName)
        put("appName", appName)
        put("title", extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "")
        put(
            "text",
            if (hide) "New notification"
            else (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: "",
        )
        put("time", sbn.postTime)
        put("canReply", findReplyAction(n) != null)
        put("isMessage", n.category == Notification.CATEGORY_MESSAGE || NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n) != null)
        extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.let { put("conversation", it.toString()) }

        // The app's own buttons (Mark as read, Like, Archive…), in the order the PC will show them.
        putJsonArray("actions") {
            buttonActions(n).forEach { a ->
                addJsonObject {
                    put("title", a.title?.toString() ?: "")
                    put("semantic", semanticName(a.semanticAction))
                }
            }
        }

        // Recent messages in the conversation, so the PC can show the thread when expanded.
        if (!hide) NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)?.let { style ->
            putJsonArray("history") {
                style.messages.takeLast(8).forEach { m ->
                    addJsonObject {
                        put("sender", m.person?.name?.toString() ?: "")
                        put("text", m.text?.toString() ?: "")
                        put("time", m.timestamp)
                        put("fromMe", m.person == null || m.person?.key == style.user.key && m.person?.name == style.user.name)
                    }
                }
            }
        }
    }

    private fun sendIconOnce(pkg: String, out: (Envelope) -> Unit, force: Boolean = false) {
        if (!iconsSent.add(pkg) && !force) return
        val icon = runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull() ?: return
        val bmp = icon.toBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bytes)
        out(msg("app.icon") {
            put("app", pkg)
            put("pngBase64", Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP))
        })
    }

    companion object {
        @Volatile private var instance: NotificationBridge? = null

        val isEnabled get() = instance != null

        /**
         * Phone buttons plus the ones apps only offer to smartwatches (that's where many chat apps
         * put Reply and Mark as read), without duplicates.
         */
        private fun allActions(n: Notification): List<Notification.Action> {
            val phone = n.actions?.toList().orEmpty()
            @Suppress("DEPRECATION")
            val watch = Notification.WearableExtender(n).actions.orEmpty()
            val seen = phone.mapNotNull { it.title?.toString()?.lowercase() }.toMutableSet()
            return phone + watch.filter { a -> a.title?.toString()?.lowercase()?.let(seen::add) ?: false }
        }

        private fun findReplyAction(n: Notification): Notification.Action? =
            allActions(n).firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }

        /** Buttons without a text box, i.e. ones you just press. */
        private fun buttonActions(n: Notification): List<Notification.Action> =
            allActions(n).filter { it.remoteInputs.isNullOrEmpty() }

        private fun semanticName(s: Int) = when (s) {
            Notification.Action.SEMANTIC_ACTION_MARK_AS_READ -> "markAsRead"
            Notification.Action.SEMANTIC_ACTION_MARK_AS_UNREAD -> "markAsUnread"
            Notification.Action.SEMANTIC_ACTION_DELETE -> "delete"
            Notification.Action.SEMANTIC_ACTION_ARCHIVE -> "archive"
            Notification.Action.SEMANTIC_ACTION_MUTE -> "mute"
            Notification.Action.SEMANTIC_ACTION_THUMBS_UP -> "like"
            Notification.Action.SEMANTIC_ACTION_CALL -> "call"
            else -> ""
        }

        private fun find(key: String): StatusBarNotification? =
            instance?.activeNotifications?.firstOrNull { it.key == key }

        /** Sends every current notification to the PC that asked (when it connects). */
        fun syncAll(out: (Envelope) -> Unit) {
            val svc = instance ?: return
            // A PC that just connected (or restarted) has none of the app icons yet: send them all again.
            val sent = mutableSetOf<String>()
            svc.activeNotifications?.filter(svc::shouldMirror)?.forEach {
                if (sent.add(it.packageName)) svc.sendIconOnce(it.packageName, out, force = true)
                out(svc.toMessage(it))
            }
        }

        /** Do Not Disturb from the PC (notification listeners may request this). */
        fun setDnd(enabled: Boolean) {
            instance?.requestInterruptionFilter(if (enabled) INTERRUPTION_FILTER_PRIORITY else INTERRUPTION_FILTER_ALL)
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

        /** Presses the notification's Nth button (same order as the "actions" list sent to the PC). */
        /** Opens what tapping the notification on the phone would open (the PC then shows the phone screen). */
        fun open(key: String) {
            val svc = instance ?: return
            val sbn = find(key) ?: return
            val pi = sbn.notification.contentIntent
            val opts = if (android.os.Build.VERSION.SDK_INT >= 34) android.app.ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle() else null
            val sent = pi != null && runCatching { pi.send(svc, 0, null, null, null, null, opts) }.isSuccess
            if (!sent) {
                // No tap action: open the app itself.
                svc.packageManager.getLaunchIntentForPackage(sbn.packageName)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { svc.startActivity(it) } }
            }
        }

        fun action(key: String, index: Int) {
            val svc = instance ?: return
            val sbn = find(key) ?: return
            buttonActions(sbn.notification).getOrNull(index)?.actionIntent?.send(svc, 0, null)
        }
    }
}
