package com.yangcoiner.fn010bridge

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView

    companion object {
        // 进程级状态:Activity 重建(切后台/转屏)后服务器不会丢失,也不会重复绑定端口
        @Volatile var ble: BleManager? = null
        @Volatile var server: ButtplugServer? = null
    }

    private val log = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        LogHelper.init(applicationContext)

        tvStatus = findViewById(R.id.tvStatus)
        findViewById<Button>(R.id.btnStart).setOnClickListener {
            if (hasPermissions()) startAll() else requestPerms()
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener { stopAll() }
        findViewById<Button>(R.id.btnExport).setOnClickListener { exportLog() }
        findViewById<Button>(R.id.btnCopy).setOnClickListener {
            val addr = "ws://" + getLocalIp() + ":12345"
            copyAddr(addr + "\nws://127.0.0.1:12345")
            Toast.makeText(this, "已复制局域网+本机两个地址", Toast.LENGTH_SHORT).show()
        }
    }

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
            status("服务器启动失败:${e.message}\n请点「全部停止」后重试。")
            b.shutdown()
            ble = null
        }

        val addr = "ws://" + getLocalIp() + ":12345"
        copyAddr(addr)
        status("Buttplug 服务器已启动(局域网地址已复制)\n" +
               "局域网连接:$addr\n" +
               "本机连接:ws://127.0.0.1:12345\n" +
               "正在扫描 FN010-RX ...")
    }

    private fun stopAll() {
        try { server?.stop() } catch (_: Exception) {}
        server = null
        ble?.shutdown()
        ble = null
        status("已停止。")
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

    private fun status(msg: String) {
        LogHelper.log("APP", msg)
        log.append(msg).append('\n')
        if (log.length > 4000) log.delete(0, log.length - 4000)
        tvStatus.text = log.toString()
    }
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

    private fun copyAddr(addr: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("fn010-ws", addr))
    }
