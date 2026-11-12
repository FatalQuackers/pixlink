package app.pixlink.files

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import androidx.documentfile.provider.DocumentFile
import app.pixlink.link.Envelope
import app.pixlink.link.msg
import app.pixlink.media.PhotoBridge
import app.pixlink.power.PowerMode
import app.pixlink.power.ShellFiles
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Files tab. Paths starting with "/" are real paths (Power mode, whole storage).
 * Paths starting with "content://" are folders the user picked (works for everyone).
 * Files sent from the PC land in Downloads/Pixlink.
 */
class FileBridge(private val context: Context) {

    private val incoming = ConcurrentHashMap<String, OutputStream>()

    /** Folders the user shared with the folder picker. */
    private val pickedFolders: List<Uri>
        get() = context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri }

    fun roots(request: Envelope) = request.reply("files.roots") {
        putJsonArray("roots") {
            if (PowerMode.isReady) addJsonObject {
                put("name", "Internal storage")
                put("path", Environment.getExternalStorageDirectory().absolutePath)
            }
            pickedFolders.forEach { uri ->
                addJsonObject {
                    put("name", DocumentFile.fromTreeUri(context, uri)?.name ?: "Folder")
                    put("path", uri.toString())
                }
            }
        }
        put("powerMode", PowerMode.isReady)
    }

    suspend fun list(request: Envelope, path: String?): Envelope {
        if (path == null) return roots(request)
        // Shell listing is a suspend call, so run it before building the reply.
        val shellEntries = if (path.startsWith("/")) ShellFiles.list(path) else emptyList()
        return request.reply("files.list") {
            put("path", path)
            putJsonArray("entries") {
                if (path.startsWith("/")) {
                    shellEntries.forEach { e ->
                        addJsonObject {
                            put("name", e.name); put("dir", e.dir); put("size", e.size); put("modified", e.modified)
                            put("path", path.trimEnd('/') + "/" + e.name)
                        }
                    }
                } else {
                    val dir = DocumentFile.fromTreeUri(context, Uri.parse(path))
                    dir?.listFiles()
                        ?.sortedWith(compareBy({ !it.isDirectory }, { it.name?.lowercase() }))
                        ?.forEach { f ->
                            addJsonObject {
                                put("name", f.name ?: "?"); put("dir", f.isDirectory)
                                put("size", f.length()); put("modified", f.lastModified())
                                put("path", f.uri.toString())
                            }
                        }
                }
            }
        }
    }

    /** Streams a file or photo to the PC as file.chunk messages. */
    suspend fun send(request: Envelope, photos: PhotoBridge, out: (Envelope) -> Unit) {
        val transferId = request.id ?: UUID.randomUUID().toString()
        val path = request.str("path")
        val photoId = request.long("photoId")

        if (path != null && path.startsWith("/")) {
            val total = ShellFiles.size(path)
            var index = 0L
            while (true) {
                val chunk = ShellFiles.readChunk(path, index, CHUNK)
                val last = chunk.size < CHUNK || (index + 1) * CHUNK >= total
                out(chunkMsg(transferId, index, last, chunk, if (index == 0L) path.substringAfterLast('/') else null, total))
                if (last) break
                index++
            }
            return
        }

        val (name, stream, size) = when {
            photoId != null -> photos.open(photoId)?.let { Triple(it.first, it.second, -1L) }
            path != null -> {
                val doc = DocumentFile.fromSingleUri(context, Uri.parse(path))
                context.contentResolver.openInputStream(Uri.parse(path))?.let { Triple(doc?.name ?: "file", it, doc?.length() ?: -1L) }
            }
            else -> null
        } ?: return
        streamOut(transferId, name, stream, size, out)
    }

    /** Sends a stream to the PC as file.chunk messages (also used by "Share to PC"). */
    fun streamOut(transferId: String, name: String, input: InputStream, size: Long, out: (Envelope) -> Unit) {
        // Reads one chunk ahead so we know which chunk is the last one.
        input.use { s ->
            var current = ByteArray(CHUNK)
            var n = readFully(s, current)
            var index = 0L
            while (true) {
                val next = ByteArray(CHUNK)
                val m = if (n == CHUNK) readFully(s, next) else 0
                val last = m == 0
                out(chunkMsg(transferId, index, last, current.copyOf(n), if (index == 0L) name else null, size))
                if (last) break
                current = next
                n = m
                index++
            }
        }
    }

    private fun readFully(s: InputStream, buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val r = s.read(buf, total, buf.size - total)
            if (r < 0) break
            total += r
        }
        return total
    }

    private fun chunkMsg(id: String, seq: Long, last: Boolean, data: ByteArray, name: String?, size: Long) =
        msg("file.chunk", id) {
            put("transferId", id)
            put("seq", seq)
            put("last", last)
            name?.let { put("name", it); put("size", size) }
            put("dataBase64", Base64.encodeToString(data, Base64.NO_WRAP))
        }

    /** PC → phone: create the destination file in Downloads/Pixlink. */
    fun beginReceive(request: Envelope) {
        val name = request.str("name") ?: "file"
        val transferId = request.str("transferId") ?: request.id ?: return
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Pixlink")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        incoming[transferId] = context.contentResolver.openOutputStream(uri)!!
        pendingUris[transferId] = uri
    }

    private val pendingUris = ConcurrentHashMap<String, Uri>()

    fun receiveChunk(m: Envelope, out: (Envelope) -> Unit) {
        val id = m.str("transferId") ?: return
        val out = incoming[id] ?: return
        out.write(Base64.decode(m.str("dataBase64") ?: "", Base64.DEFAULT))
        if (m.bool("last") == true) {
            out.close()
            incoming.remove(id)
            pendingUris.remove(id)?.let { uri ->
                context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            }
            out(msg("file.done") { put("transferId", id) })
        }
    }

    companion object { const val CHUNK = 256 * 1024 }
}
