package app.pixlink.calls

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import app.pixlink.link.LinkHub
import app.pixlink.link.msg
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Calls are placed and answered on the phone; the audio goes to the PC over
 * Bluetooth (the PC acts as a headset — see windows/Pixlink/Services/BluetoothCalls.cs).
 */
class CallBridge(private val context: Context) {

    private val telecom = context.getSystemService(TelecomManager::class.java)
    private val telephony = context.getSystemService(TelephonyManager::class.java)

    init { watchCallState() }

    private fun has(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun dial(number: String) {
        if (!has(Manifest.permission.CALL_PHONE)) return
        telecom.placeCall(Uri.fromParts("tel", number, null), null)
    }

    @SuppressLint("MissingPermission")
    fun answer() {
        if (has(Manifest.permission.ANSWER_PHONE_CALLS)) telecom.acceptRingingCall()
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun hangup() {
        if (has(Manifest.permission.ANSWER_PHONE_CALLS)) telecom.endCall()
    }

    /** Contacts with phone numbers, optionally filtered by name. */
    fun contacts(query: String?): JsonArray {
        if (!has(Manifest.permission.READ_CONTACTS)) return JsonArray(emptyList())
        val byName = linkedMapOf<String, MutableList<String>>()
        val uri = if (query.isNullOrBlank()) ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        else Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(query))

        context.contentResolver.query(
            uri,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE NOCASE ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val number = c.getString(1) ?: continue
                byName.getOrPut(name) { mutableListOf() }.apply { if (number !in this) add(number) }
            }
        }
        return buildJsonArray {
            byName.forEach { (name, numbers) ->
                addJsonObject {
                    put("name", name)
                    putJsonArray("numbers") { numbers.forEach { add(it) } }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun watchCallState() {
        if (!has(Manifest.permission.READ_PHONE_STATE)) return
        val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                val s = when (state) {
                    TelephonyManager.CALL_STATE_RINGING -> "ringing"
                    TelephonyManager.CALL_STATE_OFFHOOK -> "active"
                    else -> "ended"
                }
                LinkHub.send(msg("call.state") { put("state", s) })
            }
        }
        telephony.registerTelephonyCallback(ContextCompat.getMainExecutor(context), cb)
    }
}
