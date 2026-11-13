package app.pixlink.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.Toast
import app.pixlink.MainActivity
import app.pixlink.R
import app.pixlink.link.LinkHub
import app.pixlink.link.LinkState
import app.pixlink.link.msg
import app.pixlink.pairing.PairedStore
import kotlinx.serialization.json.put

/** Home screen widget: connection status plus Send clipboard, Send files and Find my PC. */
class PixlinkWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = update(context)

    companion object {
        const val ACTION_FIND_PC = "app.pixlink.widget.FIND_PC"

        /** Redraws every Pixlink widget (called whenever the connection changes). */
        fun update(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, PixlinkWidget::class.java))
            if (ids.isEmpty()) return
            val pcs = PairedStore(context).pcs
            val states = LinkHub.states.value
            val connected = pcs.filter { states[it.id] is LinkState.Connected }
            val title = when {
                pcs.isEmpty() -> "Pixlink"
                connected.isNotEmpty() -> connected.joinToString { it.name }
                else -> pcs.first().name
            }
            val status = when {
                pcs.isEmpty() -> "Tap to link your PC"
                connected.isNotEmpty() -> "Connected"
                else -> "Not connected"
            }
            val views = RemoteViews(context.packageName, R.layout.widget_pixlink).apply {
                setTextViewText(R.id.widget_title, title)
                setTextViewText(R.id.widget_status, status)
                setImageViewResource(R.id.widget_dot, if (connected.isNotEmpty()) R.drawable.widget_dot_on else R.drawable.widget_dot_off)
                setOnClickPendingIntent(R.id.widget_root, activity(context, 10, Intent(context, MainActivity::class.java)))
                setOnClickPendingIntent(R.id.widget_clipboard, activity(context, 11,
                    Intent(context, app.pixlink.clipboard.ClipboardSendActivity::class.java)))
                setOnClickPendingIntent(R.id.widget_files, activity(context, 12,
                    Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_SEND_FILES)))
                setOnClickPendingIntent(R.id.widget_find, PendingIntent.getBroadcast(context, 13,
                    Intent(context, WidgetActions::class.java).setAction(ACTION_FIND_PC), PendingIntent.FLAG_IMMUTABLE))
            }
            manager.updateAppWidget(ids, views)
        }

        private fun activity(context: Context, code: Int, intent: Intent) =
            PendingIntent.getActivity(context, code, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}

/** Widget buttons that work without opening the app. */
class WidgetActions : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PixlinkWidget.ACTION_FIND_PC) return
        if (LinkHub.states.value.values.none { it is LinkState.Connected }) {
            Toast.makeText(context, "Your PC isn't connected", Toast.LENGTH_SHORT).show()
            app.pixlink.link.LinkService.start(context)
            return
        }
        LinkHub.send(msg("pc.ring") { put("ring", true) })
        Toast.makeText(context, "Ringing your PC…", Toast.LENGTH_SHORT).show()
    }
}
