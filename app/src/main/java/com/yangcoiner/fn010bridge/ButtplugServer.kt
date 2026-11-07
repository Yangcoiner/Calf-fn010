package com.yangcoiner.fn010bridge

import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONArray
import org.json.JSONObject
import android.os.SystemClock
import java.net.InetSocketAddress

/**
 * Buttplug 服务器，同时兼容 v3 与 v4 两套设备描述：
 *
 *   v3: RequestServerInfo 带 MessageVersion  -> DeviceMessages + ScalarCmd
 *   v4: RequestServerInfo 带 ProtocolVersionMajor -> DeviceFeatures + OutputCmd
 *
 * 两个通道都按 100 档（0x1F..0xFF 线性）暴露，与桌面版一致：
 *   feature 0: Vibrate   0..100
 *   feature 1: Oscillate 0..100
 *
 * 版本协商很重要：新版网页播放器（the-joi-database 等）都是 v4 客户端，
 * 若回 v3 的 DeviceMessages，客户端会对 undefined 做 Object.entries 而报
 * "Cannot convert undefined or null to object"。
 */
class ButtplugServer(
    // 注意:不能叫 port —— 会与父类 WebSocketServer.getPort() 冲突(Kotlin "Accidental override")
    val listenPort: Int,
    private val ble: BleManager,
    private val onStatus: (String) -> Unit
) : WebSocketServer(InetSocketAddress(listenPort)) {

    companion object {
        const val MESSAGE_VERSION = 3          // v3 协商上限（v4 走 ProtocolVersionMajor）
        const val MAX_PING_TIME_MS = 30000
        const val SERVER_NAME = "FN010 Bridge"
        const val PING_INTERVAL_MS = 10000L    // 规范里 Ping 由服务器发，不发会被判定超时断开

        /** 差值模式下多久没收到新位置就停伸缩（只停伸缩，震动保持）。 */
        const val WATCHDOG_MS = 250L

        /** 上游彻底断流多久（一个指令都没有）→ 双停，安全兜底。 */
        const val LOST_STREAM_MS = 1500L

        const val STEPS = BleManager.STEPS
    }

    @Volatile
    private var client: WebSocket? = null

    /** 客户端协议：3 = DeviceMessages，4 = DeviceFeatures。 */
    @Volatile private var spec = 4
    @Volatile private var clientVersion = 4

    // ---- 通道屏蔽 ----
    // 屏蔽时该通道照常回 Ok（播放器察觉不到），但不下发蓝牙帧。
    // 真正的开关存在 BleManager.maskVib / maskOsc（本地滑条用 manual=true 绕过），
    // 这里只是提供服务器侧的读写入口。
    var muteVib: Boolean
        get() = ble.maskVib
        set(v) { ble.maskVib = v }
    var muteOsc: Boolean
        get() = ble.maskOsc
        set(v) { ble.maskOsc = v }

    // ---- 通道路由（联动）：上游只发一个通道时，另一通道按同档位跟随 ----
    @Volatile var mirrorVibToOsc = true
    @Volatile var mirrorOscToVib = false

    // ---- 看门狗状态 ----
    @Volatile private var running = false
    @Volatile private var lastCmdAt = 0L      // 任意指令（含 StopCmd）到达时间
    @Volatile private var lastPosAt = 0L      // 最近一次位置流（差值算法输出）时间
    @Volatile private var lastOscCmdAt = 0L   // 最近一次上游直接驱动伸缩的时间
    @Volatile private var oscOn = false       // 伸缩当前是否在跑
    @Volatile private var manualUntil = 0L    // 本地滑条操作豁免期
    private var watchdogTripped = false

    private val mutedLogAt = HashMap<String, Long>()

    init {
        isReuseAddr = true
        connectionLostTimeout = 15
    }

    override fun onStart() {
        running = true
        onStatus("Buttplug 服务器监听端口 $listenPort" +
                if (DeltaMapper.enabled) "（伸缩:差值模式）" else "（伸缩:直控模式）")
        startWatchdog()
        startPinger()
    }

    override fun onOpen(ws: WebSocket, handshake: ClientHandshake) {
        client = ws
        onStatus("客户端已连接：${ws.remoteSocketAddress}")
    }

    override fun onClose(ws: WebSocket, code: Int, reason: String, remote: Boolean) {
        if (client === ws) client = null
        // 客户端走了就停马达，避免停在长开状态
        ble.stopAll()
        oscOn = false
        onStatus("客户端断开（$code），马达已自动停止。")
    }

    override fun onError(ws: WebSocket?, ex: Exception) {
        if (ex.message?.contains("Address already in use") == true) {
            onStatus("端口 $listenPort 已被占用，请点「停止」后换个端口重试。")
        } else {
            onStatus("WebSocket 错误：${ex.message}")
        }
    }

    override fun onMessage(ws: WebSocket, message: String) {
        try {
            LogHelper.log("WS", "RX: $message")
            val msgs = JSONArray(message)
            val replies = JSONArray()
            for (i in 0 until msgs.length()) {
                handle(msgs.getJSONObject(i), replies)
            }
            if (replies.length() > 0) {
                LogHelper.log("WS", "TX: $replies")
                ws.send(replies.toString())
            }
        } catch (e: Exception) {
            ws.send(errJson(0, "Message", "消息解析失败：${e.message}").toString())
        }
    }

    private fun handle(msg: JSONObject, out: JSONArray) {
        val type = msg.names()?.getString(0) ?: return
        val body = msg.getJSONObject(type)
        val id = body.optInt("Id", 0)

        when (type) {
            "RequestServerInfo" -> {
                // 带 ProtocolVersionMajor 就是 v4 客户端；否则按 MessageVersion 协商。
                if (body.has("ProtocolVersionMajor")) {
                    spec = 4
                    clientVersion = body.optInt("ProtocolVersionMajor", 4).coerceAtLeast(1)
                } else {
                    spec = 3
                    val want = body.optInt("MessageVersion", MESSAGE_VERSION)
                    clientVersion = want.coerceIn(1, MESSAGE_VERSION)
                }
                LogHelper.log("WS", "客户端协议 v$clientVersion（${if (spec >= 4) "DeviceFeatures" else "DeviceMessages"}）")
                val info = JSONObject()
                    .put("Id", id)
                    .put("Major", 1).put("Minor", 0).put("Build", 2)
                    .put("ServerName", SERVER_NAME)
                    .put("MessageVersion", MESSAGE_VERSION)
                    .put("ProtocolVersionMajor", if (spec >= 4) 4 else 0)
                    .put("ProtocolVersionMinor", 0)
                    .put("MaxPingTime", MAX_PING_TIME_MS)
                out.put(JSONObject().put("ServerInfo", info))
                if (ble.ready) out.put(deviceAdded(id))
            }

            "RequestDeviceList" -> out.put(deviceList(id))

            "StartScanning" -> {
                // 扫描由 App 自己跑（BLE 已连上就不必再扫），只回报已有设备。
                out.put(okJson(id))
                out.put(deviceList(id))
                // v3 客户端等 ScanningFinished 才认为扫描结束，缺了会一直转圈
                if (spec < 4) {
                    out.put(JSONObject().put("ScanningFinished", JSONObject().put("Id", id)))
                }
                if (!ble.ready) onStatus("客户端请求扫描，但设备未连接，请点「启动服务器并扫描」。")
            }

            "StopScanning", "Disconnect" -> out.put(okJson(id))

            "Ping" -> out.put(okJson(id))

            // 客户端在回应我们主动发的 Ping。静默吞掉，回 Error 会让它判定协议异常直接断开。
            "Ok" -> {}

            "OutputCmd" -> handleOutputCmd(body, id, out)

            "StopCmd" -> handleStopCmd(body, id, out)

            "ScalarCmd" -> handleScalarCmd(body, id, out)

            "StopDeviceCmd", "StopAllDevices" -> {
                lastCmdAt = SystemClock.elapsedRealtime()
                DeltaMapper.reset()
                oscOn = false
                ble.stopAll()
                out.put(okJson(id))
                onStatus("$type -> 双马达已停止")
            }

            else -> out.put(errJson(id, "Unknown", "不支持的消息类型：$type"))
        }
    }

    // ------------------------------------------------------------------
    // v4：{"OutputCmd": {DeviceIndex, FeatureIndex, Command: {Vibrate: {Value: 0..100}}}}
    // ------------------------------------------------------------------
    private fun handleOutputCmd(body: JSONObject, id: Int, out: JSONArray) {
        lastCmdAt = SystemClock.elapsedRealtime()
        if (!ble.ready) {
            out.put(errJson(id, "Device", "设备未连接"))
            return
        }
        val fi = body.optInt("FeatureIndex", 0)
        val cmd = body.optJSONObject("Command")
        if (cmd == null || cmd.length() == 0) {
            out.put(errJson(id, "Message", "OutputCmd 缺少 Command"))
            return
        }
        var ok = true
        val names = cmd.keys()
        while (names.hasNext()) {
            val key = names.next()
            val raw = cmd.optJSONObject(key)?.optDouble("Value", 0.0) ?: 0.0
            val level = raw.toInt().coerceIn(0, STEPS)
            val scalar = level / STEPS.toDouble()
            LogHelper.log("WS", "OutputCmd feature=$fi $key=$level -> 第 $level 档")

            if (key == "Vibrate") {
                if (muteVib) {
                    logMuted("震动", level)
                } else {
                    if (!ble.vibrate(scalar)) ok = false
                    if (mirrorVibToOsc) mirrorOsc(scalar)
                }
            } else if (key == "Oscillate" || key == "Position" || key == "HwPositionWithDuration") {
                if (muteOsc) {
                    logMuted("伸缩", level)
                } else {
                    lastOscCmdAt = SystemClock.elapsedRealtime()
                    val sent = if (DeltaMapper.enabled) {
                        oscillateByDelta(scalar)
                    } else {
                        val r = ble.oscillate(scalar)
                        oscOn = scalar > 0.0001
                        r
                    }
                    if (!sent) ok = false
                    if (mirrorOscToVib) mirrorVib(scalar)
                }
            } else {
                out.put(errJson(id, "Device", "不支持输出类型：$key"))
                return
            }
        }
        out.put(if (ok) okJson(id) else errJson(id, "Device", "BLE 写入失败"))
    }

    // ------------------------------------------------------------------
    // v4 停止：FeatureIndex 缺省 = 全部，0 = 震动，1 = 伸缩
    // ------------------------------------------------------------------
    private fun handleStopCmd(body: JSONObject, id: Int, out: JSONArray) {
        lastCmdAt = SystemClock.elapsedRealtime()
        if (!body.has("FeatureIndex")) {
            DeltaMapper.reset()
            oscOn = false
            ble.stopAll()
            onStatus("StopCmd -> 双马达已停止")
        } else {
            when (body.optInt("FeatureIndex", -1)) {
                0 -> { ble.vibrate(0.0); onStatus("StopCmd(feature 0) -> 震动已停") }
                1 -> {
                    oscOn = false
                    ble.oscillate(0.0)
                    onStatus("StopCmd(feature 1) -> 伸缩已停")
                }
                else -> {
                    DeltaMapper.reset()
                    oscOn = false
                    ble.stopAll()
                }
            }
        }
        out.put(okJson(id))
    }

    // ------------------------------------------------------------------
    // v3：{"ScalarCmd": {Scalars: [{Index, Scalar}]}}
    // ------------------------------------------------------------------
    private fun handleScalarCmd(body: JSONObject, id: Int, out: JSONArray) {
        lastCmdAt = SystemClock.elapsedRealtime()
        if (!ble.ready) {
            out.put(errJson(id, "Device", "设备未连接"))
            return
        }
        val scalars = body.optJSONArray("Scalars")
        if (scalars == null || scalars.length() == 0) {
            out.put(errJson(id, "Message", "ScalarCmd 缺少标量参数"))
            return
        }
        val have = HashSet<Int>()
        for (i in 0 until scalars.length()) have.add(scalars.getJSONObject(i).optInt("Index", 0))

        var ok = true
        for (i in 0 until scalars.length()) {
            val s = scalars.getJSONObject(i)
            val index = s.optInt("Index", 0)
            val scalar = s.optDouble("Scalar", 0.0).coerceIn(0.0, 1.0)
            if (index == 0 && muteVib) {
                logMuted("震动", (scalar * STEPS).toInt())
                continue
            }
            if (index == 1 && muteOsc) {
                logMuted("伸缩", (scalar * STEPS).toInt())
                continue
            }
            val sent = when (index) {
                0 -> {
                    val r = ble.vibrate(scalar)
                    if (mirrorVibToOsc && 1 !in have) mirrorOsc(scalar)
                    r
                }
                1 -> {
                    lastOscCmdAt = SystemClock.elapsedRealtime()
                    val r = if (DeltaMapper.enabled) {
                        oscillateByDelta(scalar)
                    } else {
                        val x = ble.oscillate(scalar)
                        oscOn = scalar > 0.0001
                        x
                    }
                    if (mirrorOscToVib && 0 !in have) mirrorVib(scalar)
                    r
                }
                else -> false
            }
            if (!sent) ok = false
        }
        if (ok) {
            out.put(okJson(id))
            if (!DeltaMapper.enabled) onStatus("ScalarCmd 已执行")
        } else {
            out.put(errJson(id, "Device", "BLE 写入失败"))
        }
    }

    /**
     * 上游只发了震动 → 让伸缩跟着走同一个标量。
     *
     * 差值模式下这一路同样要过差值算法（Δ 越大伸缩越快），不能直接当档位用；
     * 只有直控模式才直接下发。若上游本来就在发位置流（1 秒内有过），让位给差值算法。
     */
    private fun mirrorOsc(scalar: Double) {
        if (muteOsc || !ble.ready) return
        if (DeltaMapper.enabled && SystemClock.elapsedRealtime() - lastOscCmdAt < 1000) return
        if (DeltaMapper.enabled) {
            oscillateByDelta(scalar)
        } else {
            ble.oscillate(scalar)
            oscOn = scalar > 0.0001
        }
    }

    private fun mirrorVib(scalar: Double) {
        if (muteVib || !ble.ready) return
        ble.vibrate(scalar)
    }

    /**
     * 差值模式下的伸缩通道：把上游的位置值按「位移 ÷ 时间」换算成速度档位；
     * 小冲程时改由振动通道代偿（见 DeltaMapper）。
     */
    private fun oscillateByDelta(pos: Double): Boolean {
        val now = SystemClock.elapsedRealtime()
        val r = DeltaMapper.map(pos, now)
        lastCmdAt = now
        lastPosAt = now
        watchdogTripped = false
        oscOn = r.osc > 0.0
        val okVib = if (muteVib) true else ble.vibrate(r.vib)
        val okOsc = ble.oscillate(r.osc)
        return okVib && okOsc
    }

    /** 本地滑条操作：刷新时间戳，让差值看门狗在 3 秒内不打断手动拖动。 */
    fun noteManual(osc: Boolean) {
        val now = SystemClock.elapsedRealtime()
        lastCmdAt = now
        if (osc) {
            lastPosAt = now
            manualUntil = now + 3000
        }
    }

    private fun logMuted(name: String, value: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now - (mutedLogAt[name] ?: 0L) > 3000) {
            mutedLogAt[name] = now
            onStatus("${name}通道已屏蔽：忽略上游指令（第 $value 档）")
        }
    }

    /**
     * 两个独立的看门狗，别混在一起：
     *   ① 差值模式下 250ms 没有新「位置」→ 只停伸缩。震动是另一条通道，
     *      上游只推震动时若被一起停掉，看到的就是「震一下就没」。
     *   ② 上游彻底断流 1.5s（一个指令都没有）→ 双停，安全兜底。
     */
    private fun startWatchdog() {
        Thread {
            // 自身不再是"当前服务器"实例(换端口/停止)时自动退出
            while (running && MainActivity.server === this@ButtplugServer) {
                try {
                    Thread.sleep(60)
                } catch (_: InterruptedException) {
                    break
                }
                val now = SystemClock.elapsedRealtime()

                if (DeltaMapper.enabled && oscOn && lastPosAt > 0 && now > manualUntil &&
                    now - lastPosAt > WATCHDOG_MS) {
                    oscOn = false
                    watchdogTripped = true
                    DeltaMapper.reset()
                    ble.oscillate(0.0, manual = true)   // 只停伸缩
                    onStatus("差值模式：${WATCHDOG_MS}ms 无新位置，伸缩已停（震动保持）。")
                }

                if (lastCmdAt > 0 && now - lastCmdAt > LOST_STREAM_MS) {
                    lastCmdAt = 0L
                    oscOn = false
                    DeltaMapper.reset()
                    ble.stopAll()
                    onStatus("上游 ${LOST_STREAM_MS / 1000.0}s 无任何指令，双马达已停。")
                }
            }
        }.apply { isDaemon = true }.start()
    }

    /** 规范里 Ping 由服务器发；不发的话客户端等满 MaxPingTime 就断开（每 30 秒掉一次）。 */
    private fun startPinger() {
        Thread {
            while (running && MainActivity.server === this@ButtplugServer) {
                try {
                    Thread.sleep(PING_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
                try {
                    client?.send(JSONArray().put(JSONObject().put("Ping", JSONObject().put("Id", 900000))).toString())
                } catch (_: Exception) {}
            }
        }.apply { isDaemon = true }.start()
    }

    private fun okJson(id: Int) = JSONObject().put("Ok", JSONObject().put("Id", id))

    private fun errJson(id: Int, errorType: String, message: String) =
        JSONObject().put(
            "Error",
            JSONObject().put("Id", id).put("ErrorType", errorType).put("ErrorMessage", message)
        )

    /** v4：DeviceFeatures（按 FeatureIndex 索引的对象 + Output 字典）。 */
    private fun deviceObj(): JSONObject {
        if (spec >= 4) {
            val features = JSONObject()
                .put("0", JSONObject()
                    .put("FeatureIndex", 0)
                    .put("FeatureDescriptor", "Vibrator")
                    .put("FeatureDescription", "Vibrator")
                    .put("Output", JSONObject().put("Vibrate", JSONObject()
                        .put("Value", JSONArray().put(0).put(STEPS)))))
                .put("1", JSONObject()
                    .put("FeatureIndex", 1)
                    .put("FeatureDescriptor", "Oscillator")
                    .put("FeatureDescription", "Oscillator")
                    .put("Output", JSONObject().put("Oscillate", JSONObject()
                        .put("Value", JSONArray().put(0).put(STEPS)))))
            return JSONObject()
                .put("DeviceIndex", 0)
                .put("DeviceName", BleManager.DEVICE_NAME)
                .put("DeviceMessageTimingGap", 0)
                .put("DeviceFeatures", features)
        }
        // v3：DeviceMessages + ScalarCmd
        val scalarVibrate = JSONObject()
            .put("Index", 0)
            .put("FeatureDescriptor", "Vibration")
            .put("ActuatorType", "Vibrate")
            .put("StepCount", JSONArray().put(STEPS))
            .put("Endpoints", JSONArray().put("Command"))
        val scalarOscillate = JSONObject()
            .put("Index", 1)
            .put("FeatureDescriptor", "Oscillation")
            .put("ActuatorType", "Oscillate")
            .put("StepCount", JSONArray().put(STEPS))
            .put("Endpoints", JSONArray().put("Command"))
        val messages = JSONObject()
            .put("ScalarCmd", JSONArray().put(scalarVibrate).put(scalarOscillate))
            .put("StopDeviceCmd", JSONObject())
        return JSONObject()
            .put("DeviceIndex", 0)
            .put("DeviceName", BleManager.DEVICE_NAME)
            .put("DeviceMessages", messages)
    }

    private fun deviceList(id: Int): JSONObject {
        val devices = JSONArray()
        if (ble.ready) devices.put(deviceObj())
        return JSONObject().put("DeviceList", JSONObject().put("Id", id).put("Devices", devices))
    }

    private fun deviceAdded(id: Int): JSONObject =
        JSONObject().put("DeviceAdded", deviceObj().put("Id", id))
}
