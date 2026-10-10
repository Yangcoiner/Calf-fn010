package com.yangcoiner.fn010bridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.util.Log
import java.util.UUID

/**
 * BLE driver for the ILLUSIONCLUB Belt Knight gen 2 (FN010 / broadcast name FN010-RX).
 *
 * Protocol (verified via HCI capture):
 *   handshake: write af00 to ee01, device replies on ee02: af00 03 01 00 5x
 *   command:   ac [motor] [on/off] 00 01 [speed]   (6 bytes, write-without-response)
 *              motor: 01 = vibration, 03 = oscillation
 *              speed: 0x1F + 0x19 * (level-1), 9 levels, 0x1F..0xE7
 */
@SuppressLint("MissingPermission")
class BleManager(
    private val context: Context,
    private val onStatus: (String) -> Unit
) {
    companion object {
        const val MOTOR_VIBRATION = 0x01
        const val MOTOR_OSCILLATION = 0x03
        const val BASE_SPEED = 0x1F
        const val SPEED_STEP = 0x19
        const val LEVELS = 9

        val SVC_FFee = UUID.fromString("0000ffee-0000-1000-8000-00805f9b34fb")
        val CH_WRITE = UUID.fromString("0000ee01-0000-1000-8000-00805f9b34fb")
        val CH_NOTIF = UUID.fromString("0000ee02-0000-1000-8000-00805f9b34fb")
        val DESC_CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val DEVICE_NAME = "FN010-RX"
    }

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null

    @Volatile
    var ready = false
        private set

    var onReady: (() -> Unit)? = null

    fun start() {
        val bt = context.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
        val adapter = bt.adapter
        if (adapter == null || !adapter.isEnabled) {
            onStatus("Bluetooth is off. Enable it and press Start again.")
            return
        }
        scanner = adapter.bluetoothLeScanner ?: run {
            onStatus("LE scanner unavailable.")
            return
        }

        val filter = ScanFilter.Builder().setDeviceName(DEVICE_NAME).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner?.startScan(listOf(filter), settings, scanCallback)
        onStatus("Scanning for $DEVICE_NAME ...")
    }

    private val scanCallback = object : android.bluetooth.le.ScanCallback() {
        override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
            scanner?.stopScan(this)
            onStatus("Found ${result.device.address}, connecting ...")
            connect(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            onStatus("Scan failed: $errorCode")
        }
    }

    private fun connect(device: android.bluetooth.BluetoothDevice) {
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    onStatus("BLE connected, discovering services ...")
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    ready = false
                    onStatus("BLE disconnected.")
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onStatus("Service discovery failed: $status")
                return
            }
            val svc = g.getService(SVC_FFee)
            if (svc == null) {
                onStatus("ffee service not found on this device.")
                return
            }
            val w = svc.getCharacteristic(CH_WRITE)
            val n = svc.getCharacteristic(CH_NOTIF)
            if (w == null || n == null) {
                onStatus("ee01/ee02 characteristics not found.")
                return
            }
            writeChar = w

            // Enable notifications on ee02
            g.setCharacteristicNotification(n, true)
            val cccd = n.getDescriptor(DESC_CCCD)
            if (cccd != null) {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(cccd)
            } else {
                // fall through to handshake anyway
                sendHandshake()
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            sendHandshake()
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            // Device rarely speaks; log at debug level only.
            Log.d("FN010", "notify: " + characteristic.value?.joinToString("") { "%02x".format(it) })
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendHandshake() {
        val g = gatt ?: return
        val w = writeChar ?: return
        w.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        w.value = byteArrayOf(0xaf.toByte(), 0x00)
        val ok = g.writeCharacteristic(w)
        if (ok) {
            ready = true
            onStatus("Handshake sent. Device ready.")
            onReady?.invoke()
        } else {
            onStatus("Handshake write failed.")
        }
    }

    /** Map a 0.0..1.0 scalar to the 9-level speed byte (0 => stop). */
    fun speedFromScalar(scalar: Double): Int {
        if (scalar <= 0.001) return 0
        val level = (Math.round(scalar * LEVELS)).coerceIn(1, LEVELS)
        return BASE_SPEED + SPEED_STEP * (level - 1)
    }

    @SuppressLint("MissingPermission")
    fun send(motor: Int, on: Boolean, speed: Int): Boolean {
        val g = gatt ?: return false
        val w = writeChar ?: return false
        if (!ready) return false
        val speedByte = if (on) speed else 0x00
        val frame = byteArrayOf(
            0xac.toByte(), motor.toByte(),
            (if (on) 0x01 else 0x00).toByte(),
            0x00, 0x01, speedByte.toByte()
        )
        w.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        w.value = frame
        return g.writeCharacteristic(w)
    }

    fun vibrate(scalar: Double): Boolean {
        val s = speedFromScalar(scalar)
        return send(MOTOR_VIBRATION, s > 0, s)
    }

    fun oscillate(scalar: Double): Boolean {
        val s = speedFromScalar(scalar)
        return send(MOTOR_OSCILLATION, s > 0, s)
    }

    fun stopAll() {
        send(MOTOR_VIBRATION, false, 0)
        send(MOTOR_OSCILLATION, false, 0)
    }

    @SuppressLint("MissingPermission")
    fun shutdown() {
        try { stopAll() } catch (_: Exception) {}
        ready = false
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {}
        gatt = null
        writeChar = null
    }
}
