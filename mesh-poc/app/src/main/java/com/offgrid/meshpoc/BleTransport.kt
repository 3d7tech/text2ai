package com.offgrid.meshpoc

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import android.util.Log
import com.offgrid.mesh.Transport
import com.offgrid.mesh.TransportListener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * BLE transport. Every phone is simultaneously:
 *  - a **peripheral**: advertises [SERVICE_UUID] and runs a GATT server whose [FRAME_UUID]
 *    characteristic accepts frame writes (inbound traffic);
 *  - a **central**: scans for [SERVICE_UUID], connects to peers, negotiates a large MTU and
 *    writes frames to their characteristic (outbound traffic).
 *
 * Two phones therefore usually hold two connections to each other (one each way). The mesh
 * layer de-duplicates, so that is only a bandwidth cost.
 */
@SuppressLint("MissingPermission") // permissions are requested by MainActivity before start()
class BleTransport(private val context: Context) : Transport {

    companion object {
        private const val TAG = "BleTransport"
        val SERVICE_UUID: UUID = UUID.fromString("7a1e0001-4f2b-4c8e-9a51-0ff9c1d0ae01")
        val FRAME_UUID: UUID = UUID.fromString("7a1e0002-4f2b-4c8e-9a51-0ff9c1d0ae01")
        private const val TARGET_MTU = 517
        private const val MAX_CLIENTS = 7
    }

    override val name = "ble"

    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = manager?.adapter
    private val thread = HandlerThread("ble").apply { start() }
    private val handler = Handler(thread.looper)

    private var listener: TransportListener? = null
    private var gattServer: BluetoothGattServer? = null
    private var running = false
    private val clients = ConcurrentHashMap<String, Client>()

    /** Outbound connection to one peer's GATT server. */
    private inner class Client(val address: String) {
        var gatt: BluetoothGatt? = null
        var characteristic: BluetoothGattCharacteristic? = null
        var mtu = 23
        var ready = false
        val queue = ArrayDeque<ByteArray>()
        var writing = false
    }

    // ------------------------------------------------------------------ lifecycle

    override fun start(listener: TransportListener) {
        this.listener = listener
        context.registerReceiver(stateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        handler.post { if (adapter?.isEnabled == true) startRadio() }
    }

    override fun stop() {
        runCatching { context.unregisterReceiver(stateReceiver) }
        handler.post { stopRadio() }
        thread.quitSafely()
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> handler.post { startRadio() }
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> handler.post { stopRadio() }
            }
        }
    }

    private fun startRadio() {
        if (running) return
        val adapter = adapter ?: return
        running = true
        startGattServer()
        adapter.bluetoothLeAdvertiser?.startAdvertising(
            AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .build(),
            AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(ParcelUuid(SERVICE_UUID))
                .build(),
            advertiseCallback,
        )
        adapter.bluetoothLeScanner?.startScan(
            listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build(),
            scanCallback,
        )
        Log.i(TAG, "BLE started")
    }

    private fun stopRadio() {
        if (!running) return
        running = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        for (c in clients.values) disconnect(c)
        clients.clear()
        runCatching { gattServer?.close() }
        gattServer = null
    }

    // ------------------------------------------------------------------ peripheral (inbound)

    private fun startGattServer() {
        val server = manager?.openGattServer(context, serverCallback) ?: return
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                FRAME_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            ),
        )
        server.addService(service)
        gattServer = server
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
            if (characteristic.uuid == FRAME_UUID && value != null && !preparedWrite) {
                listener?.onFrame(this@BleTransport, "in:${device.address}", value)
            }
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) { Log.w(TAG, "advertise failed: $errorCode") }
    }

    // ------------------------------------------------------------------ central (outbound)

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = result.device.address
            handler.post {
                if (!running || clients.containsKey(address) || clients.size >= MAX_CLIENTS) return@post
                val client = Client(address)
                clients[address] = client
                client.gatt = result.device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            }
        }

        override fun onScanFailed(errorCode: Int) { Log.w(TAG, "scan failed: $errorCode") }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                val c = clients[gatt.device.address] ?: run { gatt.close(); return@post }
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    gatt.requestMtu(TARGET_MTU)
                } else {
                    clients.remove(c.address)
                    disconnect(c)
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            handler.post {
                clients[gatt.device.address]?.mtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23
                gatt.discoverServices()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            handler.post {
                val c = clients[gatt.device.address] ?: return@post
                val ch = gatt.getService(SERVICE_UUID)?.getCharacteristic(FRAME_UUID)
                if (ch == null) {
                    clients.remove(c.address)
                    disconnect(c)
                    return@post
                }
                c.characteristic = ch
                c.ready = true
                listener?.onNeighborUp(this@BleTransport, c.address)
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            handler.post {
                val c = clients[gatt.device.address] ?: return@post
                c.writing = false
                pump(c)
            }
        }
    }

    private fun disconnect(c: Client) {
        val wasReady = c.ready
        c.ready = false
        c.queue.clear()
        runCatching { c.gatt?.disconnect() }
        runCatching { c.gatt?.close() }
        if (wasReady) listener?.onNeighborDown(this, c.address)
    }

    // ------------------------------------------------------------------ Transport

    override fun maxFrameSize(link: String): Int = (clients[link]?.mtu ?: 23) - 3

    override fun send(link: String, frame: ByteArray): Boolean {
        val c = clients[link] ?: return false
        if (!c.ready) return false
        handler.post {
            c.queue.addLast(frame)
            pump(c)
        }
        return true
    }

    /** GATT allows one outstanding operation per connection, so writes are serialized. */
    private fun pump(c: Client) {
        if (c.writing || !c.ready) return
        val frame = c.queue.removeFirstOrNull() ?: return
        val gatt = c.gatt ?: return
        val ch = c.characteristic ?: return
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(ch, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ch.value = frame
                gatt.writeCharacteristic(ch)
            }
        }
        if (ok) {
            c.writing = true
        } else {
            // Stack busy: retry shortly.
            c.queue.addFirst(frame)
            handler.postDelayed({ pump(c) }, 30)
        }
    }
}
