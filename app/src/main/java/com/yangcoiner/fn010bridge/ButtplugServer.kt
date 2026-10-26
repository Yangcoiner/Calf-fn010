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
 *   FeatureIndex 0: Vibrate   (level 0..100, 0 = 停)
 *   FeatureIndex 1: Oscillate (level 0..100, 0 = 停)
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
 * v4 的 Value 是绝对 level（0..100），不是 v3 那种 0.0~1.0 的比例值。
 * level 0 表示停止（发 on/off=0），1..100 映射到实测速度字节。
 */
class ButtplugServer(
    private val port: Int,
    private val ble: BleManager,
    private val onStatus: (String) -> Unit,
    /** 监听端口被系统夺走/失效时回调, 由上层重建服务器。 */
    private val onListenerDead: (() -> Unit)? = null
) : WebSocketServer(InetSocketAddress(port)) {

    companion object {
        const val PROTOCOL_VERSION_MAJOR = 4
        const val PROTOCOL_VERSION_MINOR = 0
        const val MAX_PING_TIME_MS = 30000

        /**
         * 服务器主动发 Ping 的间隔(毫秒)。
         *
         * Buttplug 规范里 Ping 是**服务器发**、客户端回 Ok。
         * 我们在 ServerInfo 里声明了 MaxPingTime=30000, 意思就是
         * "客户端最多等 30 秒就该收到我的消息"。
         * 之前从来没发过 Ping —— 播放器等满 30 秒没收到任何东西,
         * 就按正常流程 close(1000) 断开, 日志表现就是
         * "客户端断开 (1000), 马达已自动停止", 而且大约每 30 秒一次。
         *
         * 10 秒发一次, 留足余量。
         */
        const val PING_INTERVAL_MS = 10_000L
        const val SERVER_NAME = "FN010 Bridge"
        const val DEVICE_INDEX = 0

        /**
         * 客户端多久没有任何消息就认为它已经不在了（毫秒）。
         * 只用来「兜底停马达」, 不用来断连接 —— 见 init 里的说明。
         */
        const val CLIENT_STALE_MS = 60_000L

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

    /** 最近一次收到客户端消息的时刻, 用于兜底停马达。 */
    @Volatile
    private var lastClientMsgAt = 0L

    /** 连接存活监测线程。 */
    private var watchdog: java.util.Timer? = null

    // 冻结/停顿检测用: 上一次心跳的墙上时间与开机以来的 CPU 时间
    private var lastWall = 0L
    private var lastUp = 0L

    /** 服务器 -> 客户端 Ping 定时器。 */
    private var pingTimer: java.util.Timer? = null

    /** Ping 序号, 只增不减。 */
    private val pingId = java.util.concurrent.atomic.AtomicLong(1L)

    // ---- 本次连接的统计 (断线时打进日志, 便于复盘) ----
    @Volatile private var sessionStartAt = 0L
    @Volatile private var msgCount = 0
    @Volatile private var cmdCount = 0
    @Volatile private var pongCount = 0
    @Volatile private var lastCmdAt = 0L
    @Volatile private var lastCmdSummary = ""

    /** 最后一条 OutputCmd 的档位(0..100)。0 = 客户端主动归零。 */
    @Volatile private var lastLevel = -1
    @Volatile private var statsTick = 0

    /** 已经报告过监听失效, 避免刷屏/重复重建。 */
    @Volatile private var listenerDeadReported = false

    init {
        isReuseAddr = true
        // connectionLostTimeout = 15 会让 Java-WebSocket 每 15 秒发一次 ping,
        // 收不到 pong 就直接 close(1006)。这是"总是断开"的主因:
        //   * 客户端不一定会回 WebSocket 层的 pong
        //   * 熄屏 / Wi-Fi 省电 / Doze 时任何 >15s 的停顿都会被判掉线
        // 默认值是 60, 我们设 15 等于把容错压到极小。
        // 设 0 = 关闭框架的自动掉线判定, 由下面的 watchdog 自己兜底,
        // 这样网络抖动不会断连接, 而马达仍然有安全停止机制。
        connectionLostTimeout = 0
    }

    override fun onStart() {
        onStatus("Buttplug 服务器监听端口 $port (协议 v$PROTOCOL_VERSION_MAJOR)")
        lastWall = System.currentTimeMillis()
        lastUp = android.os.SystemClock.uptimeMillis()
        startWatchdog()
        startPing()
    }

    /**
     * 周期给客户端发 Ping。
     *
     * 这是保持连接不断的关键: 规范要求服务器在 MaxPingTime 内
     * 至少发一次消息, 否则客户端会认为服务器失联并主动断开(1000)。
     */
    private fun startPing() {
        pingTimer?.cancel()
        pingTimer = java.util.Timer("fn010-ping", true).also { t ->
            t.scheduleAtFixedRate(object : java.util.TimerTask() {
                override fun run() {
                    val ws = client ?: return
                    if (!ws.isOpen) return
                    try {
                        val pid = pingId.getAndIncrement()
                        ws.send(JSONArray().put(
                            JSONObject().put("Ping",
                                JSONObject().put("Id", pid))).toString())
                        LogHelper.io("WS", "TX: [{\"Ping\":{\"Id\":$pid}}]")
                    } catch (_: Exception) {
                        // 发送失败由 onClose / onError 兜底
                    }
                }
            }, PING_INTERVAL_MS, PING_INTERVAL_MS)
        }
    }

    /**
     * 监听端口自检。
     *
     * 为什么需要: 表现是"断开之后播放器怎么都连不上, 必须把 App 停掉重开"。
     * 这说明 TCP 监听已经不在了(系统回收 / accept 线程异常退出 / 端口被占),
     * 而我们的对象还活着, 界面上看起来"服务器在运行", 实际没人 listen。
     * 这里每隔 30 秒对自己做一次 TCP connect:
     *   连得上  -> 监听正常
     *   被拒绝  -> 监听已死, 通知上层重建
     */
    private fun checkListener() {
        if (listenerDeadReported) return
        // 只探测「端口还能不能绑」, 绝不建立真实连接。
        // 曾经写成 Socket().connect(127.0.0.1:port) —— 那是致命的:
        // 这个连接会被我们自己的 onOpen 当成新客户端, 于是把正在用的
        // 播放器连接当成"旧连接"关掉, 变成自己踢自己。
        var probe: java.net.ServerSocket? = null
        try {
            probe = java.net.ServerSocket()
            probe.reuseAddress = true
            probe.bind(java.net.InetSocketAddress("0.0.0.0", port))
            // 能绑上 = 原来的监听已经不在了
            listenerDeadReported = true
            LogHelper.log("LISTEN", "端口 $port 已无监听(自检可绑定) -> 请求重建")
            onStatus("监听端口已失效,正在自动重建 ...")
            onListenerDead?.invoke()
        } catch (e: java.io.IOException) {
            // 端口被占用 = 我们的监听还活着, 这是正常情况
        } catch (e: Exception) {
            LogHelper.log("LISTEN", "自检异常(忽略): ${e.javaClass.simpleName} ${e.message}")
        } finally {
            try {
                probe?.close()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 兜底监测: 客户端 60 秒没发过任何消息, 就把马达停掉。
     *
     * 为什么只停马达、不断连接:
     *   播放器暂停/拖动时本来就可能一分钟不发指令, 断连是误伤;
     *   但如果客户端真的没了(手机切走/进程被杀), 马达不能一直转。
     */
    private fun startWatchdog() {
        watchdog?.cancel()
        watchdog = java.util.Timer("fn010-watchdog", true).also { t ->
            t.scheduleAtFixedRate(object : java.util.TimerTask() {
                override fun run() {
                    // ---- 冻结检测 ----
                    // 定时器本该每 5 秒跑一次。若实际间隔远大于 5 秒, 说明进程被
                    // 暂停过(安卓 Freezer / Doze / 深度睡眠)。区分两种情形:
                    //   wall 走得比 uptime 快  -> CPU 真的睡了(深度睡眠)
                    //   wall 与 uptime 同步     -> 线程被冻/饿死, 但 CPU 在跑
                    // 这个判据能直接告诉下次是"被系统冻了"还是"自己卡住了"。
                    val nowWall = System.currentTimeMillis()
                    val nowUp = android.os.SystemClock.uptimeMillis()
                    if (lastWall > 0L) {
                        val dw = (nowWall - lastWall) / 1000.0
                        val du = (nowUp - lastUp) / 1000.0
                        if (dw > 12.0) {
                            val kind = if (dw - du > 1.0) "CPU深度睡眠" else "线程被冻结/饿死"
                            LogHelper.log("FREEZE",
                                "定时器停顿 ${String.format("%.1f", dw)}s " +
                                "(uptime只走了 ${String.format("%.1f", du)}s) -> $kind")
                        }
                    }
                    lastWall = nowWall
                    lastUp = nowUp

                    val ws = client ?: return
                    val last = lastClientMsgAt
                    if (last <= 0L) return
                    statsTick++
                    if (statsTick % 2 == 0) {
                        checkListener()
                        val alive = (System.currentTimeMillis() - sessionStartAt) / 1000.0
                        val idle = (System.currentTimeMillis() - last) / 1000.0
                        val sinceCmd = if (lastCmdAt > 0)
                            (System.currentTimeMillis() - lastCmdAt) / 1000.0 else -1.0
                        LogHelper.log("STAT",
                            "在线=${String.format("%.0f", alive)}s 指令=$cmdCount 回Ok=$pongCount " +
                            "空闲=${String.format("%.0f", idle)}s 距最后指令=${String.format("%.0f", sinceCmd)}s " +
                            (if (ws.isOpen) "socket=ok" else "socket=已关"))
                    }
                    val idle = System.currentTimeMillis() - last
                    if (idle >= CLIENT_STALE_MS) {
                        ble.stopAll()
                        lastClientMsgAt = System.currentTimeMillis()
                        onStatus("客户端已 ${idle / 1000}s 无指令,马达已自动停止(连接保留)。")
                        try {
                            ws.send(JSONArray().put(errJson(0, ERROR_UNKNOWN,
                                "客户端长时间无指令,马达已停止")).toString())
                        } catch (_: Exception) {
                        }
                    }
                }
            }, 5000L, 5000L)
        }
    }

    override fun onOpen(ws: WebSocket, handshake: ClientHandshake) {
        try {
            onOpenSafe(ws)
        } catch (e: Exception) {
            LogHelper.log("APP", "onOpen 处理异常(已吞掉): ${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun onOpenSafe(ws: WebSocket) {
        // 新客户端进来时顶掉旧的: 播放器重连时旧连接常常是半死状态,
        // 留着会让指令发到已经没人看的 socket 上。
        val old = client
        client = ws
        lastClientMsgAt = System.currentTimeMillis()
        if (old != null && old !== ws && old.isOpen) {
            try {
                old.close(1000, "被新连接替换")
            } catch (_: Exception) {
            }
        }
        sessionStartAt = System.currentTimeMillis()
        msgCount = 0
        cmdCount = 0
        pongCount = 0
        lastCmdAt = 0L
        lastCmdSummary = ""
        lastLevel = -1
        onStatus("客户端已连接:${ws.remoteSocketAddress}")
        LogHelper.section("新会话 ${ws.remoteSocketAddress}")
        LogHelper.log("APP", "会话开始 ping间隔=${PING_INTERVAL_MS}ms 版本协商前")
    }

    override fun onClose(ws: WebSocket, code: Int, reason: String, remote: Boolean) {
        // 这里的异常绝对不能逃出去。
        // Java-WebSocket 的回调是在它自己的 selector / decoder 线程上跑的,
        // 抛出去会把那个线程打死 —— 之后 accept 不再工作, 表现就是
        // "断一次以后播放器怎么都连不上, 必须把 App 停掉重开"。
        // 而 ble.stopAll() 里会碰 GATT, 完全可能抛 SecurityException。
        try {
            onCloseSafe(ws, code, reason, remote)
        } catch (e: Exception) {
            LogHelper.log("APP", "onClose 处理异常(已吞掉): ${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun onCloseSafe(ws: WebSocket, code: Int, reason: String, remote: Boolean) {
        val idleAtClose = if (lastClientMsgAt > 0)
            (System.currentTimeMillis() - lastClientMsgAt) / 1000.0 else -1.0
        if (client === ws) {
            client = null
        }
        // 安全兜底：客户端断开时停止马达
        ble.stopAll()
        val alive = if (sessionStartAt > 0)
            (System.currentTimeMillis() - sessionStartAt) / 1000.0 else 0.0
        val idle = idleAtClose
        val reasonText = when (code) {
            1000 -> "正常关闭(对端主动)"
            1001 -> "端点离开"
            1002 -> "协议错误"
            1005 -> "无状态码"
            1006 -> "异常断开(超时/网络中断/进程被冻)"
            1011 -> "服务端内部错误"
            else -> "其他"
        }
        onStatus("客户端断开 ($code),马达已自动停止。")
        // 判定: 客户端在断开前有没有把档位归零。
        //  归零 -> 大概率是用户停止播放/退出播放器, 属正常流程
        //  没归零 -> 播放中途掉线, 这才是需要排查的
        val verdict = when {
            code == 1000 && lastLevel == 0 -> "疑似正常停止(客户端先归零再关闭)"
            code == 1000 -> "播放中断开(最后档位=$lastLevel, 未归零)"
            code == 1006 -> "异常断开(无正常关闭握手)"
            else -> "其他"
        }
        // 断开后立刻确认监听是否还活着。
        // 如果 accept 线程已经死了, 等 30 秒的周期自检太慢 ——
        // 播放器早就放弃重连了(用户就得手动点停止/开启)。
        try {
            checkListener()
        } catch (_: Exception) {
        }
        LogHelper.log("APP", "会话结束 code=$code remote=$remote reason=$reasonText 判定=$verdict " +
                "时长=${String.format("%.1f", alive)}s 收包=$msgCount 指令=$cmdCount 回Ok=$pongCount " +
                "静默=${String.format("%.1f", idle)}s 最后指令=${lastCmdSummary.ifEmpty { "无" }}")
    }

    /** 停服时把监测线程一起收掉, 否则它会在后台一直跑。 */
    override fun stop() {
        watchdog?.cancel()
        watchdog = null
        pingTimer?.cancel()
        pingTimer = null
        super.stop()
    }

    override fun onError(ws: WebSocket?, ex: Exception) {
        try {
            LogHelper.log("APP", "WS 错误: ${ex.javaClass.simpleName} ${ex.message}")
        } catch (_: Exception) {
        }
        if (ex.message?.contains("Address already in use") == true) {
            onStatus("端口 12345 已被占用,请点「全部停止」后重新启动。")
        } else {
            onStatus("WebSocket 错误:${ex.message}")
        }
    }

    override fun onMessage(ws: WebSocket, message: String) {
        lastClientMsgAt = System.currentTimeMillis()
        msgCount++
        if ("OutputCmd" in message) {
            cmdCount++
            lastCmdAt = lastClientMsgAt
        }
        if ("\"Ok\"" in message) pongCount++
        try {
            LogHelper.io("WS", "RX: $message")
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
                LogHelper.io("WS", "TX: $payload")
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

            // 客户端回应我们的 Ping 时发的就是 Ok。
            // 不处理会被当成未知消息回一个 Error, 客户端可能因此判定
            // 协议异常而断开 —— 必须静默吞掉。
            "Ok" -> {
            }

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
            LogHelper.io("WS", "OutputCmd feature=$featureIndex $key value=$value -> 档位 $level")
            lastCmdSummary = "feature=$featureIndex $key=$value"
            lastLevel = value
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
