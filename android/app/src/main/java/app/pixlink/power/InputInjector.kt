package app.pixlink.power

import android.content.Context
import android.view.KeyEvent
import android.view.WindowManager
import app.pixlink.link.Envelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Turns mouse/keyboard events from the PC into touches on the phone. Power mode injects them
 * through the shell; without it, Pixlink's accessibility service performs them instead.
 * Moves are coalesced so a fast mouse never builds up a queue of stale events.
 */
object InputInjector {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<String>(Channel.UNLIMITED)
    @Volatile private var pendingMove: String? = null
    private var started = false

    fun handle(context: Context, m: Envelope) {
        if (!PowerMode.isReady) { app.pixlink.mirror.ControlService.instance?.handle(m); return }
        ensureStarted()
        val (w, h) = screenSize(context)
        fun px(key: String, size: Int) = ((m.double(key) ?: 0.0) * size).roundToInt()

        when (m.t) {
            "input.touch" -> {
                val x = px("x", w); val y = px("y", h)
                when (m.str("action")) {
                    "down" -> enqueue("input motionevent DOWN $x $y")
                    "move" -> { pendingMove = "input motionevent MOVE $x $y"; queue.trySend("") }
                    "up" -> enqueue("input motionevent UP $x $y")
                }
            }
            // Taps and swipes are sent whole by the PC: one shell command each, which is reliable.
            "input.tap" -> enqueue("input tap ${px("x", w)} ${px("y", h)}")
            "input.swipe" -> {
                val ms = (m.int("ms") ?: 300).coerceIn(50, 3000)
                enqueue("input swipe ${px("x1", w)} ${px("y1", h)} ${px("x2", w)} ${px("y2", h)} $ms")
            }
            "input.scroll" -> {
                val x = px("x", w); val y = px("y", h)
                val dy = m.double("dy") ?: 0.0
                // A short swipe scrolls on every Android version (mouse-scroll injection varies by release).
                val dist = (dy * h * 0.15).roundToInt().coerceIn(-h / 2, h / 2)
                val y2 = (y + dist).coerceIn(1, h - 1)
                enqueue("input swipe $x $y $x $y2 120")
            }
            "input.key" -> {
                m.int("keycode")?.let { enqueue("input keyevent $it") }
                m.str("text")?.let { enqueue("input text ${PowerMode.quote(it.replace(" ", "%s"))}") }
            }
            "input.nav" -> when (m.str("button")) {
                "back" -> enqueue("input keyevent ${KeyEvent.KEYCODE_BACK}")
                "home" -> enqueue("input keyevent ${KeyEvent.KEYCODE_HOME}")
                "recents" -> enqueue("input keyevent ${KeyEvent.KEYCODE_APP_SWITCH}")
            }
        }
    }

    private fun enqueue(cmd: String) {
        pendingMove?.let { queue.trySend(it); pendingMove = null }
        queue.trySend(cmd)
    }

    private fun ensureStarted() {
        if (started) return
        started = true
        scope.launch {
            for (cmd in queue) {
                val c = if (cmd.isEmpty()) pendingMove.also { pendingMove = null } ?: continue else cmd
                runCatching { PowerMode.exec(c) }
            }
        }
    }

    private fun screenSize(context: Context): Pair<Int, Int> {
        val b = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        return b.width() to b.height()
    }
}
