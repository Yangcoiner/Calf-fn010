package com.yangcoiner.fn010bridge

import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress

/**
 * Minimal Buttplug (buttplug.io) protocol server, MessageVersion 3.
 *
 * Exposes one device (index 0, "FN010-RX") with two ScalarCmd features:
 *   index 0: Vibrate   (9 steps)
 *   index 1: Oscillate (9 steps)
 * plus StopDeviceCmd / StopAllDevicesCmd.
 */
class ButtplugServer(
    port: Int,
    private val ble: BleManager,
    private val onStatus: (String) -> Unit
) : WebSocketServer(InetSocketAddress(port)) {

    companion object {
        const val MESSAGE_VERSION = 3
        const val MAX_PING_TIME_MS = 30000
        const val SERVER_NAME = "FN010 Bridge"
    }

    @Volatile
    private var client: WebSocket? = null

    init {
        isReuseAddr = true
        connectionLostTimeout = 15
    }

    override fun onStart() {
        onStatus("Buttplug 服务器监听端口 $port")
    }

    override fun onOpen(ws: WebSocket, handshake: ClientHandshake) {
        client = ws
        onStatus("客户端已连接:${ws.remoteSocketAddress}")
    }

    override fun onClose(ws: WebSocket, code: Int, reason: String, remote: Boolean) {
        if (client === ws) client = null
        // Safety: stop motors when the client goes away.
        ble.stopAll()
        onStatus("客户端断开 ($code),马达已自动停止。")
    }

    override fun onError(ws: WebSocket?, ex: Exception) {
        if (ex.message?.contains("Address already in use") == true) {
            onStatus("端口 12345 已被占用,请点「全部停止」后重新启动。")
        } else {
            onStatus("WebSocket 错误:${ex.message}")
        }
    }

    override fun onMessage(ws: WebSocket, message: String) {
        try {
            val msgs = JSONArray(message)
            val replies = JSONArray()
            for (i in 0 until msgs.length()) {
                handle(msgs.getJSONObject(i), replies)
            }
            if (replies.length() > 0) ws.send(replies.toString())
        } catch (e: Exception) {
            ws.send(errJson(0, "Message", "消息解析失败:${e.message}").toString())
        }
    }

    private fun handle(msg: JSONObject, out: JSONArray) {
        val type = msg.names()?.getString(0) ?: return
        val body = msg.getJSONObject(type)
        val id = body.optInt("Id", 0)

        when (type) {
            "RequestServerInfo" -> {
                val info = JSONObject()
                    .put("Id", id)
                    .put("Major", 1).put("Minor", 0).put("Build", 1)
                    .put("ServerName", SERVER_NAME)
                    .put("MessageVersion", MESSAGE_VERSION)
                    .put("MaxPingTime", MAX_PING_TIME_MS)
                out.put(JSONObject().put("ServerInfo", info))
                if (ble.ready) out.put(deviceAdded(id))
            }

            "RequestDeviceList" -> {
                val devices = JSONArray()
                if (ble.ready) devices.put(deviceObj())
                val list = JSONObject()
                    .put("Id", id)
                    .put("Devices", devices)
                out.put(JSONObject().put("DeviceList", list))
            }

            "StartScanning" -> {
                // Scanning is already running from the app UI; report device if present.
                out.put(okJson(id))
                if (ble.ready) out.put(deviceAdded(id))
            }

            "StopScanning" -> out.put(okJson(id))

            "Ping" -> out.put(okJson(id))

            "ScalarCmd" -> handleScalarCmd(body, id, out)

            "StopDeviceCmd" -> {
                ble.stopAll()
                out.put(okJson(id))
                onStatus("StopDeviceCmd -> 双马达已停止")
            }

            "StopAllDevices" -> {
                ble.stopAll()
                out.put(okJson(id))
                onStatus("StopAllDevices -> 双马达已停止")
            }

            else -> out.put(errJson(id, "Unknown", "不支持的消息类型:$type"))
        }
    }

    private fun handleScalarCmd(body: JSONObject, id: Int, out: JSONArray) {
        if (!ble.ready) {
            out.put(errJson(id, "Device", "设备未连接"))
            return
        }
        val scalars = body.optJSONArray("Scalars")
        if (scalars == null || scalars.length() == 0) {
            out.put(errJson(id, "Message", "ScalarCmd 缺少标量参数"))
            return
        }
        var ok = true
        for (i in 0 until scalars.length()) {
            val s = scalars.getJSONObject(i)
            val index = s.optInt("Index", 0)
            val scalar = s.optDouble("Scalar", 0.0).coerceIn(0.0, 1.0)
            val sent = when (index) {
                0 -> ble.vibrate(scalar)
                1 -> ble.oscillate(scalar)
                else -> false
            }
            if (!sent) ok = false
        }
        if (ok) {
            out.put(okJson(id))
            onStatus("ScalarCmd 已执行")
        } else {
            out.put(errJson(id, "Device", "BLE 写入失败"))
        }
    }

    private fun okJson(id: Int) = JSONObject().put("Ok", JSONObject().put("Id", id))

    private fun errJson(id: Int, errorType: String, message: String) =
        JSONObject().put(
            "Error",
            JSONObject().put("Id", id).put("ErrorType", errorType).put("ErrorMessage", message)
        )

    private fun deviceObj(): JSONObject {
        val scalarVibrate = JSONObject()
            .put("Index", 0)
            .put("FeatureDescriptor", "Vibration")
            .put("ActuatorType", "Vibrate")
            .put("StepCount", JSONArray(intArrayOf(BleManager.LEVELS)))
            .put("Endpoints", JSONArray(listOf("Command")))
        val scalarOscillate = JSONObject()
            .put("Index", 1)
            .put("FeatureDescriptor", "Oscillation")
            .put("ActuatorType", "Oscillate")
            .put("StepCount", JSONArray(intArrayOf(BleManager.LEVELS)))
            .put("Endpoints", JSONArray(listOf("Command")))
        val messages = JSONObject()
            .put("ScalarCmd", JSONArray().put(scalarVibrate).put(scalarOscillate))
            .put("StopDeviceCmd", JSONObject())
        return JSONObject()
            .put("DeviceIndex", 0)
            .put("DeviceName", BleManager.DEVICE_NAME)
            .put("DeviceMessages", messages)
    }

    private fun deviceAdded(id: Int): JSONObject {
        // DeviceAdded carries the same payload but with its own Id (0 is fine for events).
        return JSONObject().put("DeviceAdded", deviceObj().put("Id", id))
    }
}
