package app.pixlink.power

import app.pixlink.link.Envelope
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.addJsonObject

/**
 * Call history via the shell's `content` command (Power mode). This avoids declaring
 * the Play-restricted READ_CALL_LOG permission in the app.
 */
object CallLogReader {
    private val rowRegex = Regex("""number=(.*?), name=(.*?), type=(\d+), date=(\d+), duration=(\d+)""")

    suspend fun list(request: Envelope, limit: Int): Envelope {
        if (!PowerMode.isReady) return request.reply("calllog.list") { put("error", "power_mode_required") }

        val out = PowerMode.exec(
            "content query --uri content://call_log/calls " +
                "--projection number:name:type:date:duration --sort \"date DESC\""
        )
        val rows = out.lineSequence().mapNotNull { rowRegex.find(it) }.take(limit).toList()

        return request.reply("calllog.list") {
            putJsonArray("calls") {
                rows.forEach { r ->
                    val (number, name, type, date, duration) = r.destructured
                    addJsonObject {
                        put("number", number)
                        if (name != "NULL") put("name", name)
                        put("type", when (type) { "1" -> "incoming"; "2" -> "outgoing"; "3" -> "missed"; else -> "other" })
                        put("date", date.toLong())
                        put("duration", duration.toInt())
                    }
                }
            }
        }
    }
}
