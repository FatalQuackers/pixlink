package app.pixlink.clipboard

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import app.pixlink.link.LinkHub

/**
 * An invisible screen that exists only to read the clipboard (Android allows that only for the app
 * in front), send it to your PCs, and close. Opened from Pixlink's notification and quick settings tile.
 */
class ClipboardSendActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        val msg = when {
            LinkHub.connectedPcIds().isEmpty() -> "No PC connected"
            ClipboardSync.sendCurrent(this) -> "Sent to your PC"
            else -> "Nothing copied"
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        finish()
        overridePendingTransition(0, 0)
    }
}
