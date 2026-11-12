package app.pixlink.mirror

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import app.pixlink.link.Envelope
import kotlin.math.roundToInt

/**
 * Lets the PC control the phone's screen without Power mode: taps, swipes, scrolling,
 * Back/Home/Recents and typing, through Android's accessibility API. It reads nothing
 * on its own; it only acts on what the PC sends while you are mirroring.
 */
class ControlService : AccessibilityService() {

    private data class Point(val x: Float, val y: Float, val at: Long)
    private val stroke = mutableListOf<Point>()

    override fun onServiceConnected() { instance = this; announce(true) }
    override fun onDestroy() { if (instance === this) instance = null; announce(false); super.onDestroy() }

    /** Tells the PCs screen control just became available (or went away). */
    private fun announce(on: Boolean) {
        app.pixlink.link.LinkHub.send(app.pixlink.link.msg("control.state") {
            put("canControl", on || app.pixlink.power.PowerMode.isReady)
        })
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun handle(m: Envelope) {
        val b = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        val w = b.width(); val h = b.height()
        fun px(key: String, size: Int) = ((m.double(key) ?: 0.0) * size).toFloat().coerceIn(1f, size - 1f)

        when (m.t) {
            "input.touch" -> {
                val p = Point(px("x", w), px("y", h), System.currentTimeMillis())
                when (m.str("action")) {
                    "down" -> { stroke.clear(); stroke += p }
                    "move" -> if (stroke.isNotEmpty()) stroke += p
                    "up" -> if (stroke.isNotEmpty()) { stroke += p; playStroke() }
                }
            }
            "input.tap" -> line(px("x", w), px("y", h), px("x", w), px("y", h), 60)
            "input.swipe" -> line(px("x1", w), px("y1", h), px("x2", w), px("y2", h), (m.int("ms") ?: 300).coerceIn(50, 3000).toLong())
            "input.scroll" -> {
                val x = px("x", w); val y = px("y", h)
                val dist = ((m.double("dy") ?: 0.0) * h * 0.15).toFloat().coerceIn(-h / 2f, h / 2f)
                line(x, y, x, (y + dist).coerceIn(1f, h - 1f), 150)
            }
            "input.nav" -> performGlobalAction(when (m.str("button")) {
                "back" -> GLOBAL_ACTION_BACK
                "home" -> GLOBAL_ACTION_HOME
                "recents" -> GLOBAL_ACTION_RECENTS
                "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
                else -> return
            })
            "input.key" -> {
                m.str("text")?.let { type(it) }
                when (m.int("keycode")) {
                    KeyEvent.KEYCODE_DEL -> backspace()
                    KeyEvent.KEYCODE_ENTER -> enter()
                    KeyEvent.KEYCODE_BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
                }
            }
        }
    }

    private fun line(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        dispatch(path, ms)
    }

    private fun playStroke() {
        val pts = stroke.toList(); stroke.clear()
        val path = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) } }
        dispatch(path, (pts.last().at - pts.first().at).coerceIn(40, 9_000))
    }

    private fun dispatch(path: Path, ms: Long) {
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, ms)).build()
        dispatchGesture(g, null, null)
    }

    private fun focused(): AccessibilityNodeInfo? = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

    private fun setText(node: AccessibilityNodeInfo, text: String) {
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        })
    }

    private fun current(node: AccessibilityNodeInfo): String =
        if (node.isShowingHintText) "" else node.text?.toString().orEmpty()

    private fun type(text: String) { val n = focused() ?: return; setText(n, current(n) + text) }
    private fun backspace() { val n = focused() ?: return; setText(n, current(n).dropLast(1)) }
    private fun enter() {
        val n = focused() ?: return
        if (Build.VERSION.SDK_INT >= 30) n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        else type("\n")
    }

    companion object {
        @Volatile var instance: ControlService? = null
            private set

        fun isEnabled(ctx: Context): Boolean {
            if (instance != null) return true
            val flat = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = ComponentName(ctx, ControlService::class.java)
            return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
