package com.yangcoiner.fn010bridge

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvVibLevel: TextView
    private lateinit var tvOscLevel: TextView
    // 上游响应开关:true = 该马达响应上游客户端(游戏/Intiface)请求;false = 屏蔽
    private var vibGate = true
    private var oscGate = true
    private var calibrating = false

    // 锁放在 companion object: 服务器本身是进程级保留的,
    // 锁如果挂在 Activity 实例上, 转屏重建后旧实例的锁没人释放,
    // 新实例又会再拿一把 —— 既泄漏又释放不掉。

    companion object {
        // 进程级状态:Activity 重建(切后台/转屏)后服务器不会丢失,也不会重复绑定端口
        @Volatile var ble: BleManager? = null
        @Volatile var server: ButtplugServer? = null

        // 熄屏后 Wi-Fi 会进省电模式(甚至直接断), 安卓还会把后台应用冻住。
        // 没有这两个锁, WebSocket 会莫名其妙断。
        @Volatile private var wifiLock: WifiManager.WifiLock? = null
        @Volatile private var cpuLock: PowerManager.WakeLock? = null

        /**
         * 拿住 Wi-Fi 高性能锁 + CPU 部分唤醒锁, 直到停止服务。
         *
         * Wi-Fi 锁: 防止熄屏后 Wi-Fi 进 power-save / 被断开
         * CPU 锁:   防止熄屏后 CPU 休眠导致 socket 超时
         * 两者都只在服务器运行期间持有, 停止时立即释放。
         */
        @SuppressLint("WakelockTimeout")
        fun acquireLocks(ctx: Context, onMsg: (String) -> Unit) {
            try {
                if (wifiLock == null) {
                    val wm = ctx.applicationContext
                        .getSystemService(Context.WIFI_SERVICE) as WifiManager
                    wifiLock = wm.createWifiLock(
                        WifiManager.WIFI_MODE_FULL_HIGH_PERF, "fn010:bridge"
                    ).apply { setReferenceCounted(false); acquire() }
                }
            } catch (e: Exception) {
                onMsg("Wi-Fi 锁获取失败:${e.message}")
            }
            try {
                if (cpuLock == null) {
                    val pm = ctx.applicationContext
                        .getSystemService(Context.POWER_SERVICE) as PowerManager
                    cpuLock = pm.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK, "fn010:cpu"
                    ).apply { setReferenceCounted(false); acquire() }
                }
            } catch (e: Exception) {
                onMsg("唤醒锁获取失败:${e.message}")
            }
        }

        fun releaseLocks() {
            try { wifiLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
            wifiLock = null
            try { cpuLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
            cpuLock = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        LogHelper.init(applicationContext)

        tvStatus = findViewById(R.id.tvStatus)
        tvVibLevel = findViewById(R.id.tvVibLevel)
        tvOscLevel = findViewById(R.id.tvOscLevel)
        paintGates()

        val toggle = findViewById<Button>(R.id.btnToggle)
        toggle.setOnClickListener {
            if (server != null) stopAll() else {
                if (hasPermissions()) startAll() else requestPerms()
            }
        }
        findViewById<Button>(R.id.btnExport).setOnClickListener { exportLog() }

        // 详细报文开关: 关掉后只记关键事件(连接/断开/统计/错误), 日志体积小一个量级
        val chkVerbose = findViewById<android.widget.CheckBox>(R.id.chkVerbose)
        chkVerbose.isChecked = getPrefs().getBoolean("verbose_log", true)
        LogHelper.verbose = chkVerbose.isChecked
        chkVerbose.setOnCheckedChangeListener { _, checked ->
            LogHelper.verbose = checked
            getPrefs().edit().putBoolean("verbose_log", checked).apply()
            Toast.makeText(this,
                if (checked) "已开启报文级日志" else "已关闭报文级日志(仅保留关键事件)",
                Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnCopy).setOnClickListener {
            val addr = "ws://" + getLocalIp() + ":12345"
            copyAddr(addr + "\nws://127.0.0.1:12345")
            Toast.makeText(this, "已复制局域网+本机两个地址", Toast.LENGTH_SHORT).show()
        }

        // ---- 手动控制:震动/伸缩独立滑条(本地直发,不受上游开关限制) ----
        val seekVib = findViewById<SeekBar>(R.id.seekVib)
        seekVib.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                tvVibLevel.text = formatLevel("震动", progress)
                val b = ble ?: return
                b.vibrate(progress / BleManager.LEVELS.toDouble(), manual = true)
                LogHelper.log("MANUAL", "vib slider -> $progress")
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        val seekOsc = findViewById<SeekBar>(R.id.seekOsc)
        seekOsc.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                tvOscLevel.text = formatLevel("伸缩", progress)
                val b = ble ?: return
                b.oscillate(progress / BleManager.LEVELS.toDouble(), manual = true)
                LogHelper.log("MANUAL", "osc slider -> $progress")
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // ---- 上游响应开关:关掉后上游对该马达的指令一律不向蓝牙发送 ----
        findViewById<Button>(R.id.btnVib).setOnClickListener {
            val b = ble ?: run { toast("请先启动服务器并连接设备"); return@setOnClickListener }
            vibGate = !vibGate
            b.maskVib = !vibGate
            if (!vibGate) b.vibrate(0.0, manual = true) // 屏蔽时先停掉该马达
            paintGates()
            LogHelper.log("GATE", "vibration upstream -> $vibGate (mask=${b.maskVib})")
            toast(if (vibGate) "震动:响应上游请求" else "震动:已屏蔽上游请求")
        }
        findViewById<Button>(R.id.btnOsc).setOnClickListener {
            val b = ble ?: run { toast("请先启动服务器并连接设备"); return@setOnClickListener }
            oscGate = !oscGate
            b.maskOsc = !oscGate
            if (!oscGate) b.oscillate(0.0, manual = true)
            paintGates()
            LogHelper.log("GATE", "oscillation upstream -> $oscGate (mask=${b.maskOsc})")
            toast(if (oscGate) "伸缩:响应上游请求" else "伸缩:已屏蔽上游请求")
        }

        // ---- 校准:伸缩以 1 档低速运行 N 秒后自动停止,作为行程时间基准 ----
        findViewById<Button>(R.id.btnCalib).setOnClickListener {
            val b = ble ?: run { toast("请先启动服务器并连接设备"); return@setOnClickListener }
            if (calibrating) return@setOnClickListener
            calibrating = true
            val seconds = getPrefs().getFloat("calib_seconds", 3.0f)
            Toast.makeText(this, "校准中:1档伸缩 ${seconds}s 后自动停止...", Toast.LENGTH_SHORT).show()
            LogHelper.log("CALIB", "start ${seconds}s @ level 1")
            b.oscillate(1.0 / BleManager.LEVELS, manual = true)
            Thread {
                try { Thread.sleep((seconds * 1000).toLong()) } catch (_: Exception) {}
                b.oscillate(0.0, manual = true)
                runOnUiThread {
                    calibrating = false
                    status("校准完成:伸缩低速运行 ${seconds}s 后已停(时长可长按校准钮调整)。")
                }
                LogHelper.log("CALIB", "done")
            }.start()
        }
        findViewById<Button>(R.id.btnCalib).setOnLongClickListener {
            val prefs = getPrefs()
            val cur = prefs.getFloat("calib_seconds", 3.0f)
            val next = if (cur >= 10f) 1.5f else cur + 1.5f
            prefs.edit().putFloat("calib_seconds", next).apply()
            toast("校准时长:${next}s(长按循环 1.5~10s)")
            true
        }
    }

    private fun getPrefs() = getSharedPreferences("fn010", Context.MODE_PRIVATE)

    /**
     * 速度显示: level 0..100 连续。
     * 同时给出「十分制档位」便于和旧文档对照 —— 例如 level 50 显示为 "50 (5.0/10)"。
     */
    private fun formatLevel(name: String, level: Int): String {
        if (level <= 0) return "$name:关"
        val tenth = level / 10.0
        return "$name:$level (${"%.1f".format(tenth)}/10)"
    }

    private fun paintGates() {
        val bv = findViewById<Button>(R.id.btnVib)
        val bo = findViewById<Button>(R.id.btnOsc)
        bv.text = if (vibGate) "上游开" else "上游关"
        bv.setBackgroundColor(if (vibGate) 0xFF1565C0.toInt() else 0xFFD32F2F.toInt())
        bv.setTextColor(0xFFFFFFFF.toInt())
        bo.text = if (oscGate) "上游开" else "上游关"
        bo.setBackgroundColor(if (oscGate) 0xFF6A1B9A.toInt() else 0xFFD32F2F.toInt())
        bo.setTextColor(0xFFFFFFFF.toInt())
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun neededPerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasPermissions(): Boolean =
        neededPerms().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun requestPerms() {
        ActivityCompat.requestPermissions(this, neededPerms(), 1)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasPermissions()) startAll()
        else Toast.makeText(this, "需要蓝牙权限才能继续", Toast.LENGTH_LONG).show()
    }

    @SuppressLint("MissingPermission")
    private fun startAll() {
        val bt = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        if (bt.adapter == null) {
            status("未检测到蓝牙适配器。")
            return
        }
        if (server != null) {
            status("服务器已在运行中(端口 12345),无需重复启动。")
            return
        }

        val b = BleManager(this) { msg -> runOnUiThread { status(msg) } }
        ble = b
        b.start()

        val srv = ButtplugServer(12345, b) { msg -> runOnUiThread { status(msg) } }
        try {
            srv.start()
            server = srv
        } catch (e: Exception) {
            status("服务器启动失败:${e.message}\n请点「停止」后重试。")
            b.shutdown()
            ble = null
            updateToggleUi()
            return
        }

        LogHelper.section("服务器启动")
        LogHelper.log("ENV", "Android ${Build.VERSION.RELEASE}(API ${Build.VERSION.SDK_INT}) " +
                "${Build.MANUFACTURER} ${Build.MODEL}  IP=${getLocalIp()} 端口=12345")
        LogHelper.log("ENV", "详细报文=${if (LogHelper.verbose) "开" else "关"} " +
                "日志文件=${LogHelper.fileSizeKb()}KB")

        acquireNetLocks()
        // 前台服务: 熄屏后不让系统冻结进程(socket 被冻结时对端会收到 RST)
        try {
            BridgeForegroundService.start(this)
        } catch (e: Exception) {
            status("前台服务启动失败(可能缺少通知权限):${e.message}")
        }

        val addr = "ws://" + getLocalIp() + ":12345"
        copyAddr(addr)
        status("Buttplug 服务器已启动(局域网地址已复制)\n" +
               "局域网连接:$addr\n" +
               "本机连接:ws://127.0.0.1:12345\n" +
               "正在扫描 FN010-RX ...")
        updateToggleUi()
    }

    private fun stopAll() {
        try { server?.stop() } catch (_: Exception) {}
        server = null
        try {
            BridgeForegroundService.stop(this)
        } catch (_: Exception) {
        }
        releaseNetLocks()
        ble?.shutdown()
        ble = null
        vibGate = true
        oscGate = true
        paintGates()
        status("已停止。")
        updateToggleUi()
    }

    /**
     * 拿住 Wi-Fi 高性能锁 + CPU 部分唤醒锁, 直到停止服务。
     *
     * Wi-Fi 锁: 防止熄屏后 Wi-Fi 进 power-save / 被断开
     * CPU 锁:   防止熄屏后 CPU 休眠导致 socket 超时
     * 两个都是「只在服务器运行期间持有」, 停止时立刻释放, 不影响平时待机。
     */
    private fun acquireNetLocks() = acquireLocks(applicationContext) { status(it) }

    private fun releaseNetLocks() = releaseLocks()

    private fun updateToggleUi() {
        val btn = findViewById<Button>(R.id.btnToggle)
        if (server != null) {
            btn.text = "停止(马达全关)"
            btn.setBackgroundColor(0xFFD32F2F.toInt())
            btn.setTextColor(0xFFFFFFFF.toInt())
        } else {
            btn.text = "启动服务器并扫描"
            btn.setBackgroundColor(0xFF2E7D32.toInt())
            btn.setTextColor(0xFFFFFFFF.toInt())
        }
    }

    private fun exportLog() {
        try {
            // 导出的是「摘要 + 原文」的合并文件: 开头就有连断统计,
            // 不用在几万行里自己捞断连记录。
            val f = LogHelper.exportBundle(this)
            if (!f.exists() || f.length() == 0L) {
                Toast.makeText(this, "暂无日志", Toast.LENGTH_SHORT).show()
                return
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "com.yangcoiner.fn010bridge.fileprovider", f)
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(android.content.Intent.createChooser(intent, "导出调试日志"))
        } catch (e: Exception) {
            Toast.makeText(this, "导出失败:${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun copyAddr(addr: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("fn010-ws", addr))
    }

    /** 获取手机在局域网中的 IPv4 地址(非回环)。 */
    private fun getLocalIp(): String {
        return try {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }
                ?.hostAddress ?: "<未连Wi-Fi>"
        } catch (_: Exception) {
            "<获取IP失败>"
        }
    }

    private fun status(msg: String) {
        LogHelper.log("APP", msg)
        tvStatus.text = msg + "\n"
    }
}
