package com.yangcoiner.fn010bridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
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

    // BLE 写队列:Android 同一时刻只能有一个挂起的 writeCharacteristic,
    // 连续调用会静默丢包(游戏高频指令/停止包被吞的元凶)。逐包排队,回调后发下一包。
    private val writeQueue = ArrayDeque<ByteArray>()
    private var writeBusy = false

    @Volatile
    var ready = false
        private set

    // 手动遮罩:为 true 时,上游客户端(WS ScalarCmd/StopCmd)对该马达的指令
    // 一律【不向蓝牙发送】(对客户端仍返回 Ok),只有 App 手动控制能驱动该马达。
    @Volatile var maskVib = false
    @Volatile var maskOsc = false

    var onReady: (() -> Unit)? = null

    fun start() {
        val bt = context.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
        val adapter = bt.adapter
        if (adapter == null || !adapter.isEnabled) {
            onStatus("蓝牙未开启,请打开蓝牙后重试。")
            return
        }
        scanner = adapter.bluetoothLeScanner ?: run {
            onStatus("BLE 扫描器不可用。")
            return
        }

        val filter = ScanFilter.Builder().setDeviceName(DEVICE_NAME).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner?.startScan(listOf(filter), settings, scanCallback)
        onStatus("正在扫描 $DEVICE_NAME ...")
    }

    private val scanCallback = object : android.bluetooth.le.ScanCallback() {
        override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
            scanner?.stopScan(this)
            onStatus("发现设备 ${result.device.address},正在连接 ...")
            connect(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            onStatus("扫描失败:$errorCode")
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
                    onStatus("BLE 已连接,正在发现服务 ...")
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    ready = false
                    onStatus("BLE 已断开。")
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onStatus("服务发现失败:$status")
                return
            }
            val svc = g.getService(SVC_FFee)
            if (svc == null) {
                onStatus("未找到 ffee 服务,可能不是 FN010 设备。")
                return
            }
            val w = svc.getCharacteristic(CH_WRITE)
            val n = svc.getCharacteristic(CH_NOTIF)
            if (w == null || n == null) {
                onStatus("未找到 ee01/ee02 特征。")
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
            val hex = characteristic.value?.joinToString("") { "%02x".format(it) } ?: ""
            Log.d("FN010", "notify: $hex")
            LogHelper.log("BLE", "RX notify: $hex")
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int
        ) {
            synchronized(writeQueue) {
                writeBusy = false
                if (writeQueue.isNotEmpty()) writeQueue.removeFirstOrNull()
            }
            pumpWrite()
        }
    }

    /** 入队并尝试发送(一次只允许一个挂起写)。 */
    private fun enqueueWrite(frame: ByteArray): Boolean {
        val g = gatt ?: return false
        val w = writeChar ?: return false
        synchronized(writeQueue) { writeQueue.addLast(frame) }
        pumpWrite()
        return true
    }

    private fun pumpWrite() {
        val g = gatt ?: return
        val w = writeChar ?: return
        val frame = synchronized(writeQueue) {
            if (writeBusy || writeQueue.isEmpty()) return
            writeBusy = true
            writeQueue.firstOrNull() ?: return
        }
        w.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        w.value = frame
        val hex = frame.joinToString("") { "%02x".format(it) }
        val ok = g.writeCharacteristic(w)
        LogHelper.log("BLE", "TX ${if (ok) "ok" else "FAIL"}: $hex")
        if (!ok) {
            // 发送失败:释放锁并丢弃该包,防止队列卡死
            synchronized(writeQueue) {
                writeBusy = false
                writeQueue.removeFirstOrNull()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendHandshake() {
        val g = gatt ?: return
        val w = writeChar ?: return
        w.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        w.value = byteArrayOf(0xaf.toByte(), 0x00)
        val ok = g.writeCharacteristic(w) // 握手只有一包,直接发
        LogHelper.log("BLE", "TX handshake af00 -> $ok")
        if (ok) {
            ready = true
            onStatus("握手完成,设备就绪!")
            onReady?.invoke()
        } else {
            onStatus("握手发送失败。")
        }
    }

    /** Map a 0.0..1.0 scalar to the 9-level speed byte (0 => stop). */
    fun speedFromScalar(scalar: Double): Int {
        if (scalar <= 0.001) return 0
        val level = (scalar * LEVELS).toInt().coerceIn(1, LEVELS)
        return BASE_SPEED + SPEED_STEP * (level - 1)
    }

    private var lastVibFrame: ByteArray? = null
    private var lastOscFrame: ByteArray? = null

    @SuppressLint("MissingPermission")
    fun send(motor: Int, on: Boolean, speed: Int): Boolean {
        if (!ready) return false
        val speedByte = if (on) speed else 0x00
        val frame = byteArrayOf(
            0xac.toByte(), motor.toByte(),
            (if (on) 0x01 else 0x00).toByte(),
            0x00, 0x01, speedByte.toByte()
        )
        // 同帧去重:设备开环保持最后一档,重复帧没有意义,还挤占连接事件间隔
        val dup = (if (motor == MOTOR_VIBRATION) lastVibFrame else lastOscFrame)?.contentEquals(frame) == true
        if (motor == MOTOR_VIBRATION) lastVibFrame = frame else lastOscFrame = frame
        if (dup) return true
        return enqueueWrite(frame)
    }

    /**
     * manual=true 表示来自 App 手动控制/校准,无视遮罩直发;
     * 否则视为上游客户端指令,遮罩开启时直接吞掉(不发蓝牙,返回 true 让客户端收到 Ok)。
     */
    fun vibrate(scalar: Double, manual: Boolean = false): Boolean {
        if (!manual && maskVib) return true
        val s = speedFromScalar(scalar)
        return send(MOTOR_VIBRATION, s > 0, s)
    }

    fun oscillate(scalar: Double, manual: Boolean = false): Boolean {
        if (!manual && maskOsc) return true
        val s = speedFromScalar(scalar)
        return send(MOTOR_OSCILLATION, s > 0, s)
    }

    /** 上游停止指令:只停未被手动遮罩的马达,手动开着的保持不动。 */
    fun stopAll() {
        if (!maskVib) send(MOTOR_VIBRATION, false, 0)
        if (!maskOsc) send(MOTOR_OSCILLATION, false, 0)
    }

    /** App 本地的「马达全关」(停止服务器/校准收尾):无视遮罩全停。 */
    fun forceStopAll() {
        send(MOTOR_VIBRATION, false, 0)
        send(MOTOR_OSCILLATION, false, 0)
    }

    @SuppressLint("MissingPermission")
    fun shutdown() {
        try { forceStopAll() } catch (_: Exception) {}
        ready = false
        synchronized(writeQueue) { writeQueue.clear(); writeBusy = false }
        lastVibFrame = null
        lastOscFrame = null
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {}
        gatt = null
        writeChar = null
    }
}
