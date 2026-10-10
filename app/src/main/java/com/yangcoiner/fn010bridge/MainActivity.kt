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
    private var ble: BleManager? = null
    private var server: ButtplugServer? = null

    private val log = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        findViewById<Button>(R.id.btnStart).setOnClickListener {
            if (hasPermissions()) startAll() else requestPerms()
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener { stopAll() }
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
        else Toast.makeText(this, "Bluetooth permissions are required", Toast.LENGTH_LONG).show()
    }

    @SuppressLint("MissingPermission")
    private fun startAll() {
        val bt = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        if (bt.adapter == null) {
            status("No Bluetooth adapter.")
            return
        }

        ble = BleManager(this) { msg -> runOnUiThread { status(msg) } }
        ble!!.start()

        server = ButtplugServer(12345, ble!!) { msg -> runOnUiThread { status(msg) } }
        server!!.start()

        status("WebSocket server on 0.0.0.0:12345\nScanning for FN010-RX ...")
    }

    private fun stopAll() {
        try { server?.stop() } catch (_: Exception) {}
        server = null
        ble?.shutdown()
        ble = null
        status("Stopped.")
    }

    private fun status(msg: String) {
        log.append(msg).append('\n')
        if (log.length > 4000) log.delete(0, log.length - 4000)
        tvStatus.text = log.toString()
    }
}
