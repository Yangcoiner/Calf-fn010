package com.yangcoiner.fn010bridge

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
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
    // 当前监听端口(可在界面上改,持久化到 SharedPreferences)
    private var port = DEFAULT_PORT

    companion object {
        const val DEFAULT_PORT = 12345
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535
        // 进程级状态:Activity 重建(切后台/转屏)后服务器不会丢失,也不会重复绑定端口
        @Volatile var ble: BleManager? = null
        @Volatile var server: ButtplugServer? = null
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
        findViewById<Button>(R.id.btnCopy).setOnClickListener {
            val addr = "ws://" + getLocalIp() + ":" + port
            copyAddr(addr + "\nws://127.0.0.1:" + port)
            Toast.makeText(this, "已复制局域网+本机两个地址(端口 $port)", Toast.LENGTH_SHORT).show()
        }

        // ---- 端口设置:持久化保存;若服务器正在运行则自动重启以绑定新端口 ----
        port = getPrefs().getInt("port", DEFAULT_PORT)
        val etPort = findViewById<EditText>(R.id.etPort)
        etPort.setText(port.toString())
        findViewById<Button>(R.id.btnPort).setOnClickListener { applyPort(etPort) }
        findViewById<Button>(R.id.btnPortReset).setOnClickListener {
            etPort.setText(DEFAULT_PORT.toString())
            applyPort(etPort)
        }

        // ---- 伸缩映射:直控(原有) / 差值(位置→速度),默认直控 ----
        val prefs = getPrefs()
        DeltaMapper.enabled = prefs.getBoolean("delta_mode", false)
        DeltaMapper.vMaxPctPerSec = prefs.getInt("delta_vmax", 800).toDouble()
        DeltaMapper.aMinPct = prefs.getInt("delta_amin", 20).toDouble()

        val seekVmax = findViewById<SeekBar>(R.id.seekVmax)
        val seekAmin = findViewById<SeekBar>(R.id.seekAmin)
        seekVmax.progress = (DeltaMapper.vMaxPctPerSec / 200).toInt().coerceIn(1, 10)
        seekAmin.progress = (DeltaMapper.aMinPct / 5).toInt().coerceIn(0, 8)
        paintDelta()

        findViewById<Button>(R.id.btnDelta).setOnClickListener {
            DeltaMapper.enabled = !DeltaMapper.enabled
            DeltaMapper.reset()
            prefs.edit().putBoolean("delta_mode", DeltaMapper.enabled).apply()
            // 切模式时先把马达归零,避免残留档位
            ble?.forceStopAll()
            paintDelta()
            LogHelper.log("DELTA", "mode -> ${DeltaMapper.enabled}")
            toast(
                if (DeltaMapper.enabled) "伸缩:差值模式(位移/间隔→速度,小冲程走振动)"
                else "伸缩:直控模式(原有行为)"
            )
        }
        seekVmax.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = progress.coerceIn(1, 10) * 200
                DeltaMapper.vMaxPctPerSec = v.toDouble()
                prefs.edit().putInt("delta_vmax", v).apply()
                paintDelta()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                LogHelper.log("DELTA", "vmax -> ${DeltaMapper.vMaxPctPerSec}")
            }
        })
        seekAmin.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = progress.coerceIn(0, 8) * 5
                DeltaMapper.aMinPct = v.toDouble()
                prefs.edit().putInt("delta_amin", v).apply()
                paintDelta()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                LogHelper.log("DELTA", "amin -> ${DeltaMapper.aMinPct}")
            }
        })

        // ---- 手动控制:震动/伸缩独立滑条(本地直发,不受上游开关限制) ----
        val seekVib = findViewById<SeekBar>(R.id.seekVib)
        seekVib.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                tvVibLevel.text = if (progress == 0) "震动:关" else "震动:$progress 档"
                val b = ble ?: return
                b.vibrate(progress / 9.0, manual = true)
                LogHelper.log("MANUAL", "vib slider -> $progress")
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        val seekOsc = findViewById<SeekBar>(R.id.seekOsc)
        seekOsc.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                tvOscLevel.text = if (progress == 0) "伸缩:关" else "伸缩:$progress 档"
                val b = ble ?: return
                b.oscillate(progress / 9.0, manual = true)
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
            b.oscillate(1.0 / 9, manual = true)
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

    /** 读取输入框端口并应用:校验 -> 持久化 -> 运行中则重启服务。 */
    private fun applyPort(et: EditText) {
        val raw = et.text.toString().trim()
        val v = raw.toIntOrNull()
        if (v == null || v !in MIN_PORT..MAX_PORT) {
            toast("端口需为 $MIN_PORT~$MAX_PORT 之间的整数")
            et.setText(port.toString())
            return
        }
        if (v == port) {
            toast("端口未改变:$port")
            return
        }
        val old = port
        port = v
        getPrefs().edit().putInt("port", v).apply()
        LogHelper.log("PORT", "change $old -> $v (server=${server != null})")
        if (server != null) {
            restartServer()
            toast("端口已切换为 $v,服务器已重启")
        } else {
            toast("端口已设置为 $v,下次启动生效")
            status("端口已设置为 $v,点「启动服务器并扫描」后生效。")
        }
    }

    /**
     * 只重启 WebSocket 服务,保留蓝牙连接(切端口时不必重新扫描设备)。
     * BLE 为空(未启动过)则直接走完整启动流程。
     */
    private fun restartServer() {
        val b = ble
        if (b == null) {
            startAll()
            return
        }
        try { server?.stop() } catch (_: Exception) {}
        server = null
        // WebSocketServer.stop() 是异步的,短暂等待端口释放后再绑新端口
        Thread {
            try { Thread.sleep(150) } catch (_: Exception) {}
            runOnUiThread {
                val srv = ButtplugServer(port, b) { msg -> runOnUiThread { status(msg) } }
                DeltaMapper.reset()
                server = srv // 先登记,再启动(看门狗以此判断自身是否仍是当前实例)
                try {
                    srv.start()
                    val addr = "ws://" + getLocalIp() + ":" + port
                    copyAddr("$addr\nws://127.0.0.1:$port")
                    status("端口已切换为 $port(地址已复制)\n局域网连接:$addr\n本机连接:ws://127.0.0.1:$port")
                } catch (e: Exception) {
                    server = null
                    status("新端口 $port 启动失败:${e.message}")
                }
                updateToggleUi()
            }
        }.start()
    }

    private fun paintDelta() {
        val btn = findViewById<Button>(R.id.btnDelta)
        val tvVmax = findViewById<TextView>(R.id.tvVmax)
        val tvAmin = findViewById<TextView>(R.id.tvAmin)
        val on = DeltaMapper.enabled
        btn.text = if (on) "伸缩:差值(位移/间隔 → 速度)" else "伸缩:直控(位置当档位)"
        btn.setBackgroundColor(if (on) 0xFF00897B.toInt() else 0xFF455A64.toInt())
        btn.setTextColor(0xFFFFFFFF.toInt())
        tvVmax.text = "满速线:%d %%/s(到这条线就是 100%% 档)".format(DeltaMapper.vMaxPctPerSec.toInt())
        tvAmin.text = "小冲程阈值:<%d%%(以下改走振动)".format(DeltaMapper.aMinPct.toInt())
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
        // 每次启动都以当前设置(或输入框)的端口为准
        port = getPrefs().getInt("port", DEFAULT_PORT)
        findViewById<EditText>(R.id.etPort)?.setText(port.toString())

        val bt = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        if (bt.adapter == null) {
            status("未检测到蓝牙适配器。")
            return
        }
        if (server != null) {
            status("服务器已在运行中(端口 $port),无需重复启动。")
            return
        }

        val b = BleManager(this) { msg -> runOnUiThread { status(msg) } }
        ble = b
        b.start()

        val srv = ButtplugServer(port, b) { msg -> runOnUiThread { status(msg) } }
        // 先登记再启动:看门狗线程以"是否仍是当前 server"作为存活判据
        server = srv
        try {
            srv.start()
        } catch (e: Exception) {
            server = null
            status("服务器启动失败:${e.message}\n请点「停止」后重试。")
            b.shutdown()
            ble = null
            updateToggleUi()
            return
        }

        val addr = "ws://" + getLocalIp() + ":" + port
        copyAddr(addr)
        status("Buttplug 服务器已启动(局域网地址已复制)\n" +
               "局域网连接:$addr\n" +
               "本机连接:ws://127.0.0.1:$port\n" +
               "正在扫描 FN010-RX ...")
        updateToggleUi()
    }

    private fun stopAll() {
        try { server?.stop() } catch (_: Exception) {}
        server = null
        DeltaMapper.reset()
        ble?.shutdown()
        ble = null
        vibGate = true
        oscGate = true
        paintGates()
        status("已停止。")
        updateToggleUi()
    }

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
            val dir = java.io.File(getExternalFilesDir(null), "logs")
            val f = java.io.File(dir, "fn010_log.txt")
            if (!f.exists()) {
                Toast.makeText(this, "暂无日志文件", Toast.LENGTH_SHORT).show()
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
