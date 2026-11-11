package app.pixlink.power

import android.util.Base64

/** Full-storage file browsing through the shell (Power mode). */
object ShellFiles {

    data class Entry(val name: String, val dir: Boolean, val size: Long, val modified: Long)

    suspend fun list(path: String): List<Entry> {
        val p = PowerMode.quote(path.trimEnd('/'))
        // %F type, %s size, %Y mtime, %n name — one entry per line, hidden files included.
        val out = PowerMode.exec("cd $p && stat -c '%F|%s|%Y|%n' * .[!.]* 2>/dev/null")
        return out.lineSequence().mapNotNull { line ->
            val parts = line.split('|', limit = 4)
            if (parts.size < 4) return@mapNotNull null
            Entry(
                name = parts[3],
                dir = parts[0] == "directory",
                size = parts[1].toLongOrNull() ?: 0,
                modified = (parts[2].toLongOrNull() ?: 0) * 1000,
            )
        }.sortedWith(compareBy({ !it.dir }, { it.name.lowercase() })).toList()
    }

    suspend fun size(path: String): Long =
        PowerMode.exec("stat -c %s ${PowerMode.quote(path)}").trim().toLongOrNull() ?: 0

    /** Reads chunk number [index] of [chunkSize] bytes. */
    suspend fun readChunk(path: String, index: Long, chunkSize: Int): ByteArray {
        val b64 = PowerMode.exec(
            "dd if=${PowerMode.quote(path)} bs=$chunkSize skip=$index count=1 2>/dev/null | base64 -w 0"
        ).trim()
        return if (b64.isEmpty()) ByteArray(0) else Base64.decode(b64, Base64.DEFAULT)
    }
}
