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
 *              on/off: 01 = run, 00 = stop (speed byte ignored)
 *              speed:  0x00 = stop, else 0x1F..0xFF continuous
 *
 * ── 速度编码 (由 bugreport HCI 抓包重新确认) ───────────────────────────
 * 早期文档说「9 档, speed = 0x1F + 0x19*(level-1)」是不完整的。
 * 实际抓包统计到 28 个不同的速度字节, 分成两条等差主链:
 *
 *   震动 (motor=01), 10 点, 步长 25:
 *     1f 38 51 6a 83 9c b5 ce e7 ff   (31..255)
 *   伸缩 (motor=03), 9 点, 步长 22.5:
 *     35 4b 62 78 8f a5 bc d2 e9       (53..233)
 *
 * 此外还观测到主链 ±1 的「侧链」(如震动的 9b b4 cd e6, 伸缩的 4e 64 a7 bd d3),
 * 说明固件在做 **连续插值**, 而不是只有离散档位。
 *
 * 反推 level(0..100) 后两条链都落在同一区间:
 *   震动: L = 0,11,22,34,45,56,67,78,89,100
 *   伸缩: L = 10,20,30,40,50,60,70,80,90
 * 统一近似公式: byte = 31 + floor(level * 224 / 100)
 *
 * 因此本驱动按 **0..100 连续 level** 工作:
 *   - 用实测主链表做锚点(高置信度), 线性插值取中间值
 *   - level 0 保留给「停止」语义由 on/off 字节承担
 */
@SuppressLint("MissingPermission")
class BleManager(
    private val context: Context,
    private val onStatus: (String) -> Unit
) {
    companion object {
        const val MOTOR_VIBRATION = 0x01
        const val MOTOR_OSCILLATION = 0x03

        /** 连续 level 上限 (0..100)。0 不表示停, 停由 on/off 字节表示。 */
        const val LEVELS = 100

        /** 震动实测档位表 (10 点, 步长 25), HCI 抓包实证。 */
        val VIB_TABLE = intArrayOf(
            0x1F, 0x38, 0x51, 0x6A, 0x83, 0x9C, 0xB5, 0xCE, 0xE7, 0xFF
        )

        /** 伸缩实测档位表 (9 点, 步长 22.5), HCI 抓包实证。 */
        val OSC_TABLE = intArrayOf(
            0x35, 0x4B, 0x62, 0x78, 0x8F, 0xA5, 0xBC, 0xD2, 0xE9
        )

        /**
         * 按实测表把 0..100 的 level 映射到速度字节。
         * 表内是锚点, 中间做线性插值 —— 固件支持连续量, 插值出的值也有效。
         *
         * @param table       实测档位表
         * @param level       目标 level (0..100)
         * @param anchorStep  相邻锚点对应的 level 间隔
         *                    震动 = 100/9 (锚点 0,11,22,...,100), 伸缩 = 10 (锚点 10,20,...,90)
         */
        fun speedFromTable(table: IntArray, level: Int, anchorStep: Float): Int {
            val lv = level.coerceIn(0, LEVELS)
            if (lv == 0) return table[0]
            val n = table.size
            val pos = lv / anchorStep
            val i = pos.toInt().coerceIn(0, n - 2)
            val frac = pos - i
            val lo = table[i]
            val hi = table[i + 1]
            return (lo + (hi - lo) * frac).toInt().coerceIn(1, 0xFF)
        }

        /** 震动锚点间隔: 10 个锚点覆盖 level 0..100。 */
        const val VIB_ANCHOR_STEP = 100f / 9f

        /** 伸缩锚点间隔: 9 个锚点覆盖 level 10..90。 */
        const val OSC_ANCHOR_STEP = 10f

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
        val bt = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
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

    private fun connect(device: BluetoothDevice) {
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

        override fun onDescriptorWrite(g: BluetoothGatt, desc: BluetoothGattDescriptor, status: Int) {
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
                if (writeQueue.isNotEmpty()) writeQueue.removeFirstOrNull()
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

    /** 0.0..1.0 比例 -> 速度字节(震动)。 */
    fun speedFromScalar(scalar: Double): Int =
        speedFromTable(VIB_TABLE, (scalar * LEVELS).toInt().coerceIn(0, LEVELS), VIB_ANCHOR_STEP)

    /** 0.0..1.0 比例 -> 速度字节(伸缩)。 */
    fun oscSpeedFromScalar(scalar: Double): Int =
        speedFromTable(OSC_TABLE, (scalar * LEVELS).toInt().coerceIn(0, LEVELS), OSC_ANCHOR_STEP)

    /** 绝对 level(0..100) -> 速度字节(震动)。 */
    fun speedFromLevel(level: Int): Int =
        speedFromTable(VIB_TABLE, level.coerceIn(0, LEVELS), VIB_ANCHOR_STEP)

    /** 绝对 level(0..100) -> 速度字节(伸缩)。 */
    fun oscSpeedFromLevel(level: Int): Int =
        speedFromTable(OSC_TABLE, level.coerceIn(0, LEVELS), OSC_ANCHOR_STEP)

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
        val s = oscSpeedFromScalar(scalar)
        return send(MOTOR_OSCILLATION, s > 0, s)
    }

    /**
     * Buttplug v4 的 OutputCmd 用绝对 level(0..100),不是 0.0~1.0 比例。
     * 上游指令(manual=false)同样受遮罩开关约束。
     */
    fun vibrateLevel(level: Int, manual: Boolean = false): Boolean {
        if (!manual && maskVib) return true
        val s = speedFromLevel(level)
        return send(MOTOR_VIBRATION, s > 0, s)
    }

    /** Buttplug v4 的 OutputCmd 用绝对 level(0..100)。 */
    fun oscillateLevel(level: Int, manual: Boolean = false): Boolean {
        if (!manual && maskOsc) return true
        val s = oscSpeedFromLevel(level)
        return send(MOTOR_OSCILLATION, s > 0, s)
    }

    /** 上游停止指令:只停未被手动遮罩的马达,手动开着的保持不动。 */
    fun stopAll() {
        if (!maskVib) {
            lastVibFrame = null
            send(MOTOR_VIBRATION, false, 0)
        }
        if (!maskOsc) {
            lastOscFrame = null
            send(MOTOR_OSCILLATION, false, 0)
        }
    }

    /** App 本地的「马达全关」(停止服务器/校准收尾):无视遮罩全停。 */
    fun forceStopAll() {
        lastVibFrame = null
        lastOscFrame = null
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
