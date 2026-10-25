package com.yangcoiner.fn010bridge

import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress

/**
 * Buttplug 协议服务器 —— 消息规范 v4 (Buttplug Message Spec v4)。
 *
 * 暴露一台设备 (index 0, "FN010-RX")，两个 Output 特性:
 *   FeatureIndex 0: Vibrate   (档位 0..9)
 *   FeatureIndex 1: Oscillate (档位 0..9)
 *
 * ── 为什么必须是 v4 ──────────────────────────────────────────────────────
 * 新版 FunFlixDroid 内置 intiface-engine v4，只说 Buttplug 规范 v4。
 * 老版 v3 服务器会「connected 但 Devices 为空」：握手能过（老客户端宽容），
 * 但设备列表结构对不上，客户端解析不出来，于是列表为空。
 *
 * v4 与 v3 的关键差异（已用官方 buttplug JS 客户端 v5.0.2 实测验证）:
 *   1. ServerInfo 用 ProtocolVersionMajor/Minor，不再用 MessageVersion
 *   2. DeviceList.Devices 是 map（key = 设备索引），不再是数组
 *   3. 设备用 DeviceFeatures（map），不再是 DeviceMessages
 *   4. 输出指令是 OutputCmd { DeviceIndex, FeatureIndex, Command: { Vibrate: { Value } } }
 *   5. 停止指令是 StopCmd { DeviceIndex?, FeatureIndex?, Inputs, Outputs }
 *   6. **所有出站消息必须包在 JSON 数组里** —— 规范要求单元素数组包裹，
 *      客户端直接对结果做迭代，发裸对象会抛 TypeError
 *
 * v4 的 Value 是绝对档位值（0..9），不是 v3 那种 0.0~1.0 的比例值。
 */
class ButtplugServer(
    port: Int,
    private val ble: BleManager,
    private val onStatus: (String) -> Unit
) : WebSocketServer(InetSocketAddress(port)) {

    companion object {
        const val PROTOCOL_VERSION_MAJOR = 4
        const val PROTOCOL_VERSION_MINOR = 0
        const val MAX_PING_TIME_MS = 30000
        const val SERVER_NAME = "FN010 Bridge"
        const val DEVICE_INDEX = 0

        // Buttplug 错误码
        const val ERROR_UNKNOWN = 0
        const val ERROR_MSG = 3
        const val ERROR_DEVICE = 4
    }

    @Volatile
    private var client: WebSocket? = null

    /** 客户端协商到的协议版本，握手后才有值。 */
    @Volatile
    private var clientProtocolMajor: Int = -1

    init {
        isReuseAddr = true
        connectionLostTimeout = 15
    }

    override fun onStart() {
        onStatus("Buttplug 服务器监听端口 $port (协议 v$PROTOCOL_VERSION_MAJOR)")
    }

    override fun onOpen(ws: WebSocket, handshake: ClientHandshake) {
        client = ws
        onStatus("客户端已连接:${ws.remoteSocketAddress}")
    }

    override fun onClose(ws: WebSocket, code: Int, reason: String, remote: Boolean) {
        if (client === ws) client = null
        // 安全兜底：客户端断开时停止马达
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
            LogHelper.log("WS", "RX: $message")
            val trimmed = message.trim()
            // 客户端可能发单对象，也可能发数组；统一成数组处理
            val msgs = if (trimmed.startsWith("[")) {
                JSONArray(trimmed)
            } else {
                JSONArray().put(JSONObject(trimmed))
            }
            val replies = JSONArray()
            for (i in 0 until msgs.length()) {
                handle(msgs.getJSONObject(i), replies)
            }
            if (replies.length() > 0) {
                // 关键：所有出站消息必须包在数组里（规范要求单元素数组包裹）
                val payload = replies.toString()
                LogHelper.log("WS", "TX: $payload")
                ws.send(payload)
            }
        } catch (e: Exception) {
            try {
                ws.send(JSONArray().put(errJson(0, ERROR_MSG, "消息解析失败:${e.message}")).toString())
            } catch (_: Exception) {
                // 发送失败就算了，连接层会兜底
            }
        }
    }

    private fun handle(msg: JSONObject, out: JSONArray) {
        val type = msg.names()?.getString(0) ?: return
        val body = msg.optJSONObject(type) ?: JSONObject()
        val id = body.optInt("Id", 0)

        when (type) {
            "RequestServerInfo" -> {
                clientProtocolMajor = body.optInt("ProtocolVersionMajor", -1)
                val info = JSONObject()
                    .put("Id", id)
                    .put("ProtocolVersionMajor", PROTOCOL_VERSION_MAJOR)
                    .put("ProtocolVersionMinor", PROTOCOL_VERSION_MINOR)
                    .put("MaxPingTime", MAX_PING_TIME_MS)
                    .put("ServerName", SERVER_NAME)
                out.put(JSONObject().put("ServerInfo", info))
                LogHelper.log("WS", "握手完成,客户端协议版本=$clientProtocolMajor")
            }

            "Ping" -> out.put(okJson(id))

            "StartScanning" -> {
                // 扫描一直在跑，这里直接回报设备
                out.put(okJson(id))
                if (ble.ready) out.put(deviceListJson(0))
            }

            "StopScanning" -> out.put(okJson(id))

            "RequestDeviceList" -> out.put(deviceListJson(id))

            "OutputCmd" -> handleOutputCmd(body, id, out)

            "StopCmd" -> handleStopCmd(body, id, out)

            "InputCmd" -> out.put(errJson(id, ERROR_DEVICE, "FN010 没有输入特性"))

            "Disconnect" -> out.put(okJson(id))

            else -> out.put(errJson(id, ERROR_UNKNOWN, "不支持的消息类型:$type"))
        }
    }

    /**
     * v4 输出指令: OutputCmd { DeviceIndex, FeatureIndex, Command: { Vibrate: { Value } } }
     * Value 是绝对 level（0..100），不是比例。
     */
    private fun handleOutputCmd(body: JSONObject, id: Int, out: JSONArray) {
        if (!ble.ready) {
            out.put(errJson(id, ERROR_DEVICE, "设备未连接"))
            return
        }
        val featureIndex = body.optInt("FeatureIndex", -1)
        val command = body.optJSONObject("Command")
        if (command == null || command.length() == 0) {
            out.put(errJson(id, ERROR_MSG, "OutputCmd 缺少 Command"))
            return
        }
        var ok = true
        for (key in command.keys()) {
            val payload = command.optJSONObject(key)
            val value = payload?.optInt("Value", 0) ?: 0
            val level = value.coerceIn(0, BleManager.LEVELS)
            LogHelper.log("WS", "OutputCmd feature=$featureIndex $key value=$value -> 档位 $level")
            val sent = when (key) {
                "Vibrate" -> ble.vibrateLevel(level)
                "Oscillate" -> ble.oscillateLevel(level)
                else -> {
                    out.put(errJson(id, ERROR_DEVICE, "不支持的输出类型:$key"))
                    return
                }
            }
            if (!sent) ok = false
        }
        if (ok) {
            out.put(okJson(id))
        } else {
            out.put(errJson(id, ERROR_DEVICE, "BLE 写入失败"))
        }
    }

    /** v4 停止指令: StopCmd { DeviceIndex?, FeatureIndex?, Inputs, Outputs } */
    private fun handleStopCmd(body: JSONObject, id: Int, out: JSONArray) {
        // FeatureIndex 缺省表示停全部特性
        val featureIndex = if (body.has("FeatureIndex") && !body.isNull("FeatureIndex")) {
            body.optInt("FeatureIndex", -1)
        } else {
            -1
        }
        when (featureIndex) {
            -1 -> {
                ble.stopAll()
                onStatus("StopCmd -> 双马达已停止")
            }
            0 -> {
                ble.vibrateLevel(0)
                onStatus("StopCmd(feature 0) -> 震动已停止")
            }
            1 -> {
                ble.oscillateLevel(0)
                onStatus("StopCmd(feature 1) -> 伸缩已停止")
            }
        }
        out.put(okJson(id))
    }

    private fun okJson(id: Int) = JSONObject().put("Ok", JSONObject().put("Id", id))

    private fun errJson(id: Int, errorCode: Int, message: String) =
        JSONObject().put(
            "Error",
            JSONObject().put("Id", id).put("ErrorCode", errorCode).put("ErrorMessage", message)
        )

    /**
     * v4 DeviceList: Devices 是 map（key = 设备索引的字符串形式），不是数组。
     */
    private fun deviceListJson(id: Int): JSONObject {
        val devices = JSONObject()
        if (ble.ready) {
            devices.put(DEVICE_INDEX.toString(), deviceObj())
        }
        return JSONObject().put("DeviceList", JSONObject().put("Id", id).put("Devices", devices))
    }

    /**
     * v4 设备条目: DeviceFeatures 是 map（feature index -> feature）。
     * Output 也是 map: { "Vibrate": { "Value": [min, max] } }
     *
     * Value 范围用 0..100 (不是 0..9): HCI 抓包证明设备支持 0..100 连续调速,
     * 声明成 0..9 会白白丢掉 10 倍分辨率, 慢速段落无法精确还原。
     */
    private fun deviceObj(): JSONObject {
        val vibrateOutput = JSONObject()
            .put("Vibrate", JSONObject().put("Value", JSONArray().put(0).put(BleManager.LEVELS)))
        val oscillateOutput = JSONObject()
            .put("Oscillate", JSONObject().put("Value", JSONArray().put(0).put(BleManager.LEVELS)))

        val featureVibrate = JSONObject()
            .put("FeatureIndex", 0)
            .put("FeatureDescription", "Vibrator")
            .put("Output", vibrateOutput)
        val featureOscillate = JSONObject()
            .put("FeatureIndex", 1)
            .put("FeatureDescription", "Oscillator")
            .put("Output", oscillateOutput)

        val features = JSONObject()
            .put("0", featureVibrate)
            .put("1", featureOscillate)

        return JSONObject()
            .put("DeviceIndex", DEVICE_INDEX)
            .put("DeviceName", BleManager.DEVICE_NAME)
            .put("DeviceMessageTimingGap", 0)
            .put("DeviceFeatures", features)
    }
}
