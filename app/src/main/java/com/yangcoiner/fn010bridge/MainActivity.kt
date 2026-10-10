package com.yangcoiner.fn010bridge

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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
    private lateinit var tvLevel: TextView
    private var manualVib = false
    private var manualOsc = false
    private var calibrating = false

    companion object {
        // 进程级状态:Activity 重建(切后台/转屏)后服务器不会丢失,也不会重复绑定端口
        @Volatile var ble: BleManager? = null
        @Volatile var server: ButtplugServer? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        LogHelper.init(applicationContext)

        tvStatus = findViewById(R.id.tvStatus)
        tvLevel = findViewById(R.id.tvLevel)
        updateToggleUi()

        val toggle = findViewById<Button>(R.id.btnToggle)
        toggle.setOnClickListener {
            if (server != null) stopAll() else {
                if (hasPermissions()) startAll() else requestPerms()
            }
        }
        findViewById<Button>(R.id.btnExport).setOnClickListener { exportLog() }
        findViewById<Button>(R.id.btnCopy).setOnClickListener {
            val addr = "ws://" + getLocalIp() + ":12345"
            copyAddr(addr + "\nws://127.0.0.1:12345")
            Toast.makeText(this, "已复制局域网+本机两个地址", Toast.LENGTH_SHORT).show()
        }

        // ---- 手动控制区 ----
        val seek = findViewById<SeekBar>(R.id.seekLevel)
        seek.max = 8 // 0..8 => 档位 1..9
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                tvLevel.text = "档位:${progress + 1}"
                val b = ble ?: return
                if (manualVib) b.vibrate((progress + 1) / 9.0, manual = true)
                if (manualOsc) b.oscillate((progress + 1) / 9.0, manual = true)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        findViewById<Button>(R.id.btnVib).setOnClickListener {
            val b = ble ?: run { toast("请先启动服务器并连接设备"); return@setOnClickListener }
            manualVib = !manualVib
            b.maskVib = manualVib // 遮罩:开启后上游客户端调不动震动马达
            b.vibrate(if (manualVib) level() / 9.0 else 0.0, manual = true)
            paintManual()
            LogHelper.log("MANUAL", "vibration -> $manualVib (level ${level()}, mask=${b.maskVib})")
        }
        findViewById<Button>(R.id.btnOsc).setOnClickListener {
            val b = ble ?: run { toast("请先启动服务器并连接设备"); return@setOnClickListener }
            manualOsc = !manualOsc
            b.maskOsc = manualOsc // 遮罩:开启后上游客户端调不动伸缩马达
            b.oscillate(if (manualOsc) level() / 9.0 else 0.0, manual = true)
            paintManual()
            LogHelper.log("MANUAL", "oscillation -> $manualOsc (level ${level()}, mask=${b.maskOsc})")
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
                manualOsc = false
                runOnUiThread {
                    calibrating = false
                    paintManual()
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

    private fun level(): Int = findViewById<SeekBar>(R.id.seekLevel).progress + 1

    private fun getPrefs() = getSharedPreferences("fn010", Context.MODE_PRIVATE)

    private fun paintManual() {
        val bv = findViewById<Button>(R.id.btnVib)
        val bo = findViewById<Button>(R.id.btnOsc)
        bv.text = if (manualVib) "震动:开" else "只开震动"
        bv.setBackgroundColor(if (manualVib) 0xFF1565C0.toInt() else 0xFF455A64.toInt())
        bv.setTextColor(0xFFFFFFFF.toInt())
        bo.text = if (manualOsc) "伸缩:开" else "只开伸缩"
        bo.setBackgroundColor(if (manualOsc) 0xFF6A1B9A.toInt() else 0xFF455A64.toInt())
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
        ble?.shutdown()
        ble = null
        manualVib = false
        manualOsc = false
        paintManual()
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
