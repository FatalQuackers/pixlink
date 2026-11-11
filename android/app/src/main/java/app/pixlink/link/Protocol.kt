package app.pixlink.link

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** One protocol message. See docs/PROTOCOL.md. */
@Serializable
data class Envelope(
    val t: String,
    val id: String? = null,
    val d: JsonObject = JsonObject(emptyMap()),
) {
    fun str(key: String): String? = (d[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    fun int(key: String): Int? = d[key]?.jsonPrimitive?.intOrNull
    fun long(key: String): Long? = d[key]?.jsonPrimitive?.longOrNull
    fun double(key: String): Double? = d[key]?.jsonPrimitive?.doubleOrNull
    fun bool(key: String): Boolean? = d[key]?.jsonPrimitive?.booleanOrNull

    /** Builds a reply that echoes this message's id. */
    fun reply(t: String, build: JsonObjectBuilder.() -> Unit = {}) = msg(t, id, build)
}

val Codec = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
}

fun msg(t: String, id: String? = null, build: JsonObjectBuilder.() -> Unit = {}) =
    Envelope(t, id, buildJsonObject(build))


object MessageTypes {
    const val PAIR_REQUEST = "pair.request"
    const val PAIR_ACCEPT = "pair.accept"
    const val PAIR_REJECT = "pair.reject"
    const val HELLO = "hello"
    const val STATUS = "status"
    const val PING = "ping"
    const val PONG = "pong"
}
