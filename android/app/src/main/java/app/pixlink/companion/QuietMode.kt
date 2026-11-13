package app.pixlink.companion

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanFilter
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.ParcelUuid
import app.pixlink.link.LinkService
import app.pixlink.pairing.PairedStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Quiet mode: Pixlink without the always-on notification.
 *
 * Android only lets an app keep running in the background without a notification if it's the
 * companion app of a nearby device. So the phone becomes the "companion" of the PC: Android's own
 * Companion Device Manager asks once to link them over Bluetooth, then wakes Pixlink whenever the
 * PC is nearby (and lets it use the network in the background). The PC has to opt in too
 * (Settings → Connections → Let my phone find this PC over Bluetooth), which makes it advertise
 * the same Bluetooth LE service iPhones use.
 */
object QuietMode {
    private val SERVICE_UUID: ParcelUuid = ParcelUuid.fromString("6f3c1e9a-2b7d-4c5e-9a1f-8d2b3c4e5f61")
    private val PC_ID_UUID: UUID = UUID.fromString("6f3c1e9a-2b7d-4c5e-9a1f-8d2b3c4e5f62")

    fun isSupported(ctx: Context) = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)

    private fun cdm(ctx: Context) = ctx.getSystemService(CompanionDeviceManager::class.java)

    /** On, and Android still has the link (it's removed if the user unlinks it in system settings). */
    fun isActive(ctx: Context): Boolean {
        val store = PairedStore(ctx)
        if (!store.quietMode || store.companionAssociation < 0 || !isSupported(ctx)) return false
        return runCatching { cdm(ctx).myAssociations.any { it.id == store.companionAssociation } }.getOrDefault(false)
    }

    /** Step 1: Android looks for the PC over Bluetooth and asks the user to confirm. */
    fun requestAssociation(
        ctx: Context,
        onPending: (IntentSender) -> Unit,
        onCreated: (AssociationInfo) -> Unit,
        onError: (String) -> Unit,
    ) {
        val filter = BluetoothLeDeviceFilter.Builder()
            .setScanFilter(ScanFilter.Builder().setServiceUuid(SERVICE_UUID).build())
            .build()
        val request = AssociationRequest.Builder()
            .addDeviceFilter(filter)
            .setSingleDevice(true)
            .build()
        runCatching {
            cdm(ctx).associate(request, ctx.mainExecutor, object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(intentSender: IntentSender) = onPending(intentSender)
                override fun onAssociationCreated(associationInfo: AssociationInfo) = onCreated(associationInfo)
                override fun onFailure(error: CharSequence?) =
                    onError("Couldn't find your PC. Keep Tethr Link open on it with Bluetooth on, and check Let my phone find this PC over Bluetooth is on in its settings.")
            })
        }.onFailure { onError(it.message ?: "Android couldn't start Bluetooth pairing.") }
    }

    /**
     * Step 2: connect to the PC, pair (reading its id needs an encrypted link) and check it's one of
     * ours. Then ask Android to wake Pixlink whenever it's nearby. Returns an error, or null when on.
     */
    suspend fun finish(ctx: Context, info: AssociationInfo): String? {
        val store = PairedStore(ctx)
        val address = info.deviceMacAddress?.toString()?.uppercase()
            ?: return "Android didn't give Tethr Link the PC's Bluetooth address. Try again."
        val pcId = readPcId(ctx, address)
        if (pcId == null) {
            runCatching { cdm(ctx).disassociate(info.id) }
            return "Couldn't connect to your PC over Bluetooth. Accept the pairing request on both the phone and the PC, then try again."
        }
        if (store.pcs.none { it.id.equals(pcId, ignoreCase = true) }) {
            runCatching { cdm(ctx).disassociate(info.id) }
            return "That's a different PC. Pick the PC this phone is linked to."
        }
        store.companionAssociation = info.id
        store.companionAddress = address
        store.quietMode = true
        @Suppress("DEPRECATION")
        runCatching { cdm(ctx).startObservingDevicePresence(address) }
        LinkService.restart(ctx)
        return null
    }

    fun disable(ctx: Context) {
        val store = PairedStore(ctx)
        val cdm = cdm(ctx)
        @Suppress("DEPRECATION")
        store.companionAddress?.let { runCatching { cdm.stopObservingDevicePresence(it) } }
        if (store.companionAssociation >= 0) runCatching { cdm.disassociate(store.companionAssociation) }
        store.quietMode = false
        store.companionAssociation = -1
        store.companionAddress = null
        LinkService.restart(ctx)
    }

    /** Reads the PC id characteristic, retrying while Android pairs the two (up to ~60 s for the dialogs). */
    @SuppressLint("MissingPermission")
    private suspend fun readPcId(ctx: Context, address: String): String? {
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return null
        var gatt: BluetoothGatt? = null
        val ready = CompletableDeferred<BluetoothGattCharacteristic?>()
        var readResult = CompletableDeferred<String?>()

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) g.discoverServices()
                else if (newState == BluetoothProfile.STATE_DISCONNECTED) { ready.complete(null); readResult.complete(null) }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                ready.complete(g.getService(SERVICE_UUID.uuid)?.getCharacteristic(PC_ID_UUID))
            }

            override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                readResult.complete(if (status == BluetoothGatt.GATT_SUCCESS) String(value) else null)
            }
        }

        return try {
            gatt = device.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
            val characteristic = withTimeoutOrNull(20_000) { ready.await() } ?: return null
            withTimeoutOrNull(60_000) {
                var id: String? = null
                while (id == null) {
                    readResult = CompletableDeferred()
                    gatt?.readCharacteristic(characteristic)
                    id = withTimeoutOrNull(8_000) { readResult.await() }
                    // Not paired yet: Android shows the pairing request; ask for it if it hasn't.
                    if (id == null) {
                        if (device.bondState == BluetoothDevice.BOND_NONE) device.createBond()
                        delay(3_000)
                    }
                }
                id
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { gatt?.disconnect(); gatt?.close() }
        }
    }
}
