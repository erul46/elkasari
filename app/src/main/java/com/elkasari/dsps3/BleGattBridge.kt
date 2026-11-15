package com.elkasari.dsps3

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.ArrayAdapter
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Native Android 8.0+ (API 26+) BLE GATT Bridge untuk ELKASARI DSP S3.
 * Menyediakan antarmuka navigator.bluetooth agar protokol komunikasi dua arah
 * di dalam file HTML berjalan 100% identik tanpa mengubah satu baris pun kode DSP.
 */
class BleGattBridge(
    private val activity: Activity,
    private val webView: WebView,
    private val ensureBlePermissions: ((Boolean) -> Unit) -> Unit
) {
    companion object {
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val TARGET_DSP_SERVICE = "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val bluetoothManager = activity.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var scanner: BluetoothLeScanner? = null

    private var activeGatt: BluetoothGatt? = null
    private var connectedAddress: String? = null
    private var pendingConnectCallbackId: String? = null

    private data class ScannedItem(
        val device: BluetoothDevice,
        var name: String,
        val address: String,
        var rssi: Int,
        var hasDspService: Boolean
    )

    private data class GattOp(
        val type: String, // "READ" | "WRITE" | "NOTIFY"
        val callbackId: String,
        val serviceUuid: String,
        val charUuid: String,
        val payload: ByteArray?,
        val withResponse: Boolean
    )

    private val opQueue = ConcurrentLinkedQueue<GattOp>()
    private var currentOp: GattOp? = null
    private val opLock = Any()
    private val opTimeoutRunnable = Runnable {
        synchronized(opLock) {
            val timedOut = currentOp
            if (timedOut != null) {
                currentOp = null
                rejectJs(timedOut.callbackId, "BLE operation timeout")
                processNextOp()
            }
        }
    }

    private var activeScanCallback: ScanCallback? = null
    private var pickerDialog: AlertDialog? = null

    @JavascriptInterface
    fun requestDevice(callbackId: String, optionsJson: String) {
        mainHandler.post {
            ensureBlePermissions { granted ->
                if (!granted) {
                    rejectJs(callbackId, "Izin Bluetooth / Lokasi ditolak")
                    return@ensureBlePermissions
                }
                val adapter = bluetoothAdapter
                if (adapter == null || !adapter.isEnabled) {
                    rejectJs(callbackId, "Bluetooth HP tidak aktif. Aktifkan Bluetooth terlebih dahulu.")
                    return@ensureBlePermissions
                }
                showBleScannerDialog(callbackId)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun showBleScannerDialog(callbackId: String) {
        stopActiveScan()
        pickerDialog?.dismiss()

        val items = mutableListOf<ScannedItem>()
        val seenAddresses = HashMap<String, ScannedItem>()
        var resolved = false

        val adapter = object : ArrayAdapter<ScannedItem>(
            activity,
            android.R.layout.simple_list_item_2,
            android.R.id.text1,
            items
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                view.setBackgroundColor(Color.parseColor("#11151F"))
                val item = items[position]
                val t1 = view.findViewById<TextView>(android.R.id.text1)
                val t2 = view.findViewById<TextView>(android.R.id.text2)
                t1.setTextColor(if (item.hasDspService) Color.parseColor("#00E5FF") else Color.parseColor("#F1F5F9"))
                t1.textSize = 14f
                t1.text = if (item.hasDspService) "⚡ ${item.name} (DSP)" else item.name
                t2.setTextColor(Color.parseColor("#94A3B8"))
                t2.textSize = 11f
                t2.text = "${item.address}  •  Signal: ${item.rssi} dBm"
                return view
            }
        }

        val scanCb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val dev = result.device ?: return
                val addr = dev.address ?: return
                val devName = try {
                    result.scanRecord?.deviceName ?: dev.name ?: "BLE Device"
                } catch (_: SecurityException) {
                    "BLE Device"
                }
                val hasDsp = result.scanRecord?.serviceUuids?.any {
                    it.uuid.toString().equals(TARGET_DSP_SERVICE, ignoreCase = true)
                } == true || devName.contains("ELKASARI", ignoreCase = true) || devName.contains("DSP", ignoreCase = true)

                mainHandler.post {
                    val existing = seenAddresses[addr]
                    if (existing != null) {
                        existing.rssi = result.rssi
                        if (devName != "BLE Device") existing.name = devName
                        if (hasDsp) existing.hasDspService = true
                    } else {
                        val item = ScannedItem(dev, devName, addr, result.rssi, hasDsp)
                        seenAddresses[addr] = item
                        if (hasDsp) items.add(0, item) else items.add(item)
                    }
                    adapter.notifyDataSetChanged()
                }
            }

            override fun onScanFailed(errorCode: Int) {
                mainHandler.post {
                    if (!resolved) {
                        resolved = true
                        stopActiveScan()
                        pickerDialog?.dismiss()
                        rejectJs(callbackId, "BLE scan gagal (kode $errorCode). Pastikan Lokasi/GPS aktif di Android 8-11.")
                    }
                }
            }
        }

        activeScanCallback = scanCb
        scanner = bluetoothAdapter?.bluetoothLeScanner
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner?.startScan(null, settings, scanCb)
        } catch (e: Exception) {
            rejectJs(callbackId, "Gagal memulai scan BLE: ${e.message}")
            return
        }

        val dialog = AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Pilih Perangkat BLE DSP ESP32-S3")
            .setAdapter(adapter) { d, which ->
                resolved = true
                stopActiveScan()
                d.dismiss()
                val chosen = items[which]
                val res = JSONObject().apply {
                    put("id", chosen.address)
                    put("name", chosen.name)
                }
                resolveJs(callbackId, res.toString())
            }
            .setNegativeButton("Batal") { d, _ ->
                resolved = true
                stopActiveScan()
                d.dismiss()
                rejectJs(callbackId, "User cancelled the requestDevice() chooser.")
            }
            .setOnCancelListener {
                if (!resolved) {
                    resolved = true
                    stopActiveScan()
                    rejectJs(callbackId, "User cancelled the requestDevice() chooser.")
                }
            }
            .create()

        pickerDialog = dialog
        dialog.show()
    }

    @SuppressLint("MissingPermission")
    private fun stopActiveScan() {
        val cb = activeScanCallback ?: return
        activeScanCallback = null
        try {
            scanner?.stopScan(cb)
        } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    @JavascriptInterface
    fun connect(callbackId: String, address: String) {
        mainHandler.post {
            stopActiveScan()
            val adapter = bluetoothAdapter
            if (adapter == null) {
                rejectJs(callbackId, "Bluetooth adapter tidak tersedia")
                return@post
            }

            closeActiveGatt()
            val device = try {
                adapter.getRemoteDevice(address)
            } catch (e: Exception) {
                rejectJs(callbackId, "Alamat BLE tidak valid: ${e.message}")
                return@post
            }

            pendingConnectCallbackId = callbackId
            connectedAddress = address
            activeGatt = device.connectGatt(activity, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }
    }

    @SuppressLint("MissingPermission")
    @JavascriptInterface
    fun disconnect(address: String) {
        mainHandler.post {
            closeActiveGatt()
            emitJsDisconnected(address)
        }
    }

    @JavascriptInterface
    fun readCharacteristic(callbackId: String, serviceUuid: String, charUuid: String) {
        enqueueOp(GattOp("READ", callbackId, serviceUuid, charUuid, null, true))
    }

    @JavascriptInterface
    fun writeCharacteristic(
        callbackId: String,
        serviceUuid: String,
        charUuid: String,
        base64Data: String,
        withResponse: Boolean
    ) {
        val bytes = Base64.decode(base64Data, Base64.DEFAULT)
        enqueueOp(GattOp("WRITE", callbackId, serviceUuid, charUuid, bytes, withResponse))
    }

    @JavascriptInterface
    fun startNotifications(callbackId: String, serviceUuid: String, charUuid: String) {
        enqueueOp(GattOp("NOTIFY", callbackId, serviceUuid, charUuid, null, true))
    }

    private fun enqueueOp(op: GattOp) {
        synchronized(opLock) {
            opQueue.add(op)
            if (currentOp == null) {
                processNextOp()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun processNextOp() {
        val op: GattOp
        synchronized(opLock) {
            if (currentOp != null) return
            val next = opQueue.poll() ?: return
            currentOp = next
            op = next
        }

        val gatt = activeGatt
        if (gatt == null) {
            finishCurrentOpError("Perangkat BLE belum terhubung")
            return
        }

        val service = gatt.getService(UUID.fromString(op.serviceUuid))
        val char = service?.getCharacteristic(UUID.fromString(op.charUuid))
        if (char == null) {
            finishCurrentOpError("Characteristic tidak ditemukan: ${op.charUuid}")
            return
        }

        mainHandler.removeCallbacks(opTimeoutRunnable)
        mainHandler.postDelayed(opTimeoutRunnable, 5000)

        when (op.type) {
            "READ" -> {
                val ok = gatt.readCharacteristic(char)
                if (!ok) finishCurrentOpError("readCharacteristic gagal dimulai")
            }
            "WRITE" -> {
                val data = op.payload ?: ByteArray(0)
                val writeType = if (op.withResponse) {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                } else {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                }
                val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(char, data, writeType) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        char.writeType = writeType
                        char.value = data
                        gatt.writeCharacteristic(char)
                    }
                }
                if (!started) finishCurrentOpError("writeCharacteristic gagal dimulai")
            }
            "NOTIFY" -> {
                gatt.setCharacteristicNotification(char, true)
                val cccd = char.getDescriptor(CCCD_UUID)
                if (cccd == null) {
                    finishCurrentOpSuccess("true")
                    return
                }
                val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(cccd)
                    }
                }
                if (!started) finishCurrentOpError("Gagal mengaktifkan notifikasi CCCD")
            }
        }
    }

    private fun finishCurrentOpSuccess(jsonResult: String) {
        mainHandler.removeCallbacks(opTimeoutRunnable)
        val op: GattOp?
        synchronized(opLock) {
            op = currentOp
            currentOp = null
        }
        if (op != null) resolveJs(op.callbackId, jsonResult)
        synchronized(opLock) { processNextOp() }
    }

    private fun finishCurrentOpError(message: String) {
        mainHandler.removeCallbacks(opTimeoutRunnable)
        val op: GattOp?
        synchronized(opLock) {
            op = currentOp
            currentOp = null
        }
        if (op != null) rejectJs(op.callbackId, message)
        synchronized(opLock) { processNextOp() }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                val mtuRequested = try { gatt.requestMtu(247) } catch (_: Exception) { false }
                if (!mtuRequested) {
                    gatt.discoverServices()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val pending = pendingConnectCallbackId
                pendingConnectCallbackId = null
                val addr = connectedAddress ?: gatt.device.address ?: ""
                closeActiveGatt()
                if (pending != null) {
                    rejectJs(pending, "Koneksi BLE terputus (status $status)")
                } else {
                    emitJsDisconnected(addr)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val cb = pendingConnectCallbackId
            pendingConnectCallbackId = null
            if (cb == null) return

            if (status != BluetoothGatt.GATT_SUCCESS) {
                rejectJs(cb, "Gagal membaca layanan GATT (status $status)")
                return
            }

            val svcArray = JSONArray()
            for (svc in gatt.services) {
                val charArray = JSONArray()
                for (c in svc.characteristics) {
                    charArray.put(JSONObject().apply {
                        put("uuid", c.uuid.toString().lowercase())
                        put("properties", c.properties)
                    })
                }
                svcArray.put(JSONObject().apply {
                    put("uuid", svc.uuid.toString().lowercase())
                    put("characteristics", charArray)
                })
            }
            resolveJs(cb, JSONObject().put("services", svcArray).toString())
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                finishCurrentOpSuccess("true")
            } else {
                finishCurrentOpError("GATT write error: $status")
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                finishCurrentOpSuccess("true")
            } else {
                finishCurrentOpError("GATT descriptor write error: $status")
            }
        }

        // Android 8 - 12 (API 26 - 32)
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val b64 = Base64.encodeToString(characteristic.value ?: ByteArray(0), Base64.NO_WRAP)
                finishCurrentOpSuccess(JSONObject.quote(b64))
            } else {
                finishCurrentOpError("GATT read error: $status")
            }
        }

        // Android 13+ (API 33+)
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val b64 = Base64.encodeToString(value, Base64.NO_WRAP)
                finishCurrentOpSuccess(JSONObject.quote(b64))
            } else {
                finishCurrentOpError("GATT read error: $status")
            }
        }

        // Android 8 - 12 (API 26 - 32)
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            emitJsNotification(
                characteristic.service.uuid.toString().lowercase(),
                characteristic.uuid.toString().lowercase(),
                characteristic.value ?: ByteArray(0)
            )
        }

        // Android 13+ (API 33+)
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            emitJsNotification(
                characteristic.service.uuid.toString().lowercase(),
                characteristic.uuid.toString().lowercase(),
                value
            )
        }
    }

    private fun emitJsNotification(serviceUuid: String, charUuid: String, data: ByteArray) {
        val b64 = Base64.encodeToString(data, Base64.NO_WRAP)
        val js = "window.__androidBle && window.__androidBle._onNotify(${JSONObject.quote(serviceUuid)}, ${JSONObject.quote(charUuid)}, ${JSONObject.quote(b64)});"
        mainHandler.post { webView.evaluateJavascript(js, null) }
    }

    private fun emitJsDisconnected(address: String) {
        val js = "window.__androidBle && window.__androidBle._onDisconnect(${JSONObject.quote(address)});"
        mainHandler.post { webView.evaluateJavascript(js, null) }
    }

    private fun resolveJs(callbackId: String, jsonLiteral: String) {
        val js = "window.__androidBle && window.__androidBle._resolve(${JSONObject.quote(callbackId)}, $jsonLiteral);"
        mainHandler.post { webView.evaluateJavascript(js, null) }
    }

    private fun rejectJs(callbackId: String, errorMsg: String) {
        val js = "window.__androidBle && window.__androidBle._reject(${JSONObject.quote(callbackId)}, ${JSONObject.quote(errorMsg)});"
        mainHandler.post { webView.evaluateJavascript(js, null) }
    }

    @SuppressLint("MissingPermission")
    fun closeActiveGatt() {
        mainHandler.removeCallbacks(opTimeoutRunnable)
        synchronized(opLock) {
            currentOp = null
            opQueue.clear()
        }
        try {
            activeGatt?.disconnect()
            activeGatt?.close()
        } catch (_: Exception) {}
        activeGatt = null
        connectedAddress = null
    }
}
