package app.pixlink.share

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import app.pixlink.files.FileBridge
import app.pixlink.link.LinkHub
import app.pixlink.link.msg
import kotlinx.serialization.json.put
import java.util.UUID
import kotlin.concurrent.thread

/**
 * "Send to PC" in Android's share menu. Text and links go to the PC's clipboard;
 * photos and files are saved in the PC's Downloads\Pixlink folder.
 */
class ShareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!LinkHub.isConnected) {
            Toast.makeText(this, "Pixlink isn't connected to your PC", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.streamUri())
            Intent.ACTION_SEND_MULTIPLE -> intent.streamUris()
            else -> emptyList()
        }
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)

        when {
            uris.isNotEmpty() -> {
                val app = applicationContext
                thread(name = "pixlink-share") {
                    val files = FileBridge(app)
                    for (uri in uris) {
                        val (name, size) = describe(uri)
                        contentResolver.openInputStream(uri)?.let { files.streamOut(UUID.randomUUID().toString(), name, it, size) }
                    }
                }
                Toast.makeText(this, if (uris.size == 1) "Sending to your PC…" else "Sending ${uris.size} files to your PC…", Toast.LENGTH_SHORT).show()
            }
            !text.isNullOrBlank() -> {
                LinkHub.send(msg("share.text") { put("text", text) })
                Toast.makeText(this, "Copied to your PC's clipboard", Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }

    private fun describe(uri: Uri): Pair<String, Long> {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) return (c.getString(0) ?: "shared-file") to (if (c.isNull(1)) -1L else c.getLong(1))
        }
        return (uri.lastPathSegment ?: "shared-file") to -1L
    }

    @Suppress("DEPRECATION")
    private fun Intent.streamUri(): Uri? = getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun Intent.streamUris(): List<Uri> = getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
}
