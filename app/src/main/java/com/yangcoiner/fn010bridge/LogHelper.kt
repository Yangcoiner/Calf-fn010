package com.yangcoiner.fn010bridge

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 调试日志: 内存环形缓冲 + 追加写文件 + 自动轮转 + 导出摘要。
 *
 * ── 为什么要分级 ──────────────────────────────────────────────────────
 * 以前所有报文都往日志里灌, 16 分钟就 2.6MB (34000 行), 里面 95% 是
 * OutputCmd 的全文。真正有用的是"什么时候连上、什么时候断、断在哪一步"。
 * 现在分成两级:
 *   log(tag, msg)  关键事件 —— 连接/断开/握手/错误/统计, 始终记录
 *   io(tag, msg)   报文全文 —— 只在「详细报文日志」打开时记录
 *
 * ── 为什么要轮转 ──────────────────────────────────────────────────────
 * 文件无上限增长, 导出的包越来越大, 旧的关键事件还会被淹没。
 * 超过 MAX_BYTES 就滚到 fn010_log.1.txt, 只保留一代。
 *
 * ── 导出时为什么多一段摘要 ────────────────────────────────────────────
 * 直接发原始日志, 读的人要先在几万行里捞断连行。导出现在会在文件开头
 * 塞一段自动统计(连接次数、断开码分布、每段会话时长与指令数),
 * 后面才是原文。
 */
object LogHelper {
    private const val MAX_LINES = 4000
    private const val MAX_BYTES = 2L * 1024 * 1024      // 2MB 轮转

    private val buf = ArrayDeque<String>()
    private var logFile: File? = null
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val fmtDay = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /** true = 记录报文全文(RX/TX 的 JSON)。 */
    @Volatile
    var verbose: Boolean = true

    fun init(context: Context) {
        val dir = File(context.getExternalFilesDir(null), "logs")
        dir.mkdirs()
        logFile = File(dir, "fn010_log.txt")
    }

    @Synchronized
    fun log(tag: String, msg: String) {
        val line = "${fmt.format(Date())} [$tag] $msg"
        buf.addLast(line)
        while (buf.size > MAX_LINES) buf.removeFirst()
        append(line)
    }

    /** 报文级日志: 只有开详细模式才写。 */
    @Synchronized
    fun io(tag: String, msg: String) {
        if (!verbose) return
        log(tag, msg)
    }

    /** 写一段醒目的分隔 + 环境信息, 便于在长日志里定位一次运行。 */
    @Synchronized
    fun section(title: String) {
        log("=====", "==================== $title ====================")
    }

    @Synchronized
    fun dump(): String = buf.joinToString("\n")

    fun fileSizeKb(): Long = try {
        (logFile?.length() ?: 0L) / 1024
    } catch (_: Exception) {
        0L
    }

    private fun append(line: String) {
        try {
            val f = logFile ?: return
            if (f.length() > MAX_BYTES) rotate(f)
            f.appendText(line + "\n")
        } catch (_: Exception) {
        }
    }

    private fun rotate(f: File) {
        try {
            val prev = File(f.parentFile, "fn010_log.1.txt")
            if (prev.exists()) prev.delete()
            f.renameTo(prev)
        } catch (_: Exception) {
        }
    }

    /**
     * 生成可直接发送的日志包: 摘要 + 原始日志。
     *
     * 摘要完全从日志行里统计出来, 不依赖运行时状态 ——
     * 这样即使进程中途被杀, 摘要也覆盖整个文件。
     */
    @Synchronized
    fun exportBundle(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "logs")
        dir.mkdirs()
        val out = File(dir, "fn010_log_export.txt")

        val raw = StringBuilder()
        val prev = File(dir, "fn010_log.1.txt")
        if (prev.exists()) {
            try {
                raw.append(prev.readText())
            } catch (_: Exception) {
            }
        }
        try {
            logFile?.let { raw.append(it.readText()) }
        } catch (_: Exception) {
        }
        val text = raw.toString()
        val summary = buildSummary(context, text)
        out.writeText(summary + "\n\n" + text)
        return out
    }

    private fun buildSummary(context: Context, text: String): String {
        val lines = text.lineSequence().toList()
        val connectRe = Regex("""(\d\d-\d\d \d\d:\d\d:\d\d\.\d+) \[APP\] 客户端已连接""")
        val closeRe = Regex("""(\d\d-\d\d \d\d:\d\d:\d\d\.\d+) \[APP\] 客户端断开 \((\d+)\)""")

        var connects = 0
        var cmds = 0
        var errors = 0
        val codes = LinkedHashMap<String, Int>()
        val sessions = ArrayList<String>()
        var lastConnect: String? = null
        var cmdsInSession = 0

        for (l in lines) {
            if ("OutputCmd" in l && "RX" in l) {
                cmds++
                cmdsInSession++
            }
            if ("\"Error\"" in l) errors++
            val c = connectRe.find(l)
            if (c != null) {
                connects++
                lastConnect = c.groupValues[1]
                cmdsInSession = 0
                continue
            }
            val d = closeRe.find(l)
            if (d != null) {
                val code = d.groupValues[2]
                codes[code] = (codes[code] ?: 0) + 1
                sessions.add("  ${lastConnect ?: "?"} -> ${d.groupValues[1]}  指令 ${cmdsInSession} 条  code=$code")
                lastConnect = null
            }
        }

        val sb = StringBuilder()
        sb.append("=== FN010 Bridge 诊断摘要 ===\n")
        sb.append("生成时间: ${fmtDay.format(Date())}\n")
        sb.append("应用版本: ${appVersion(context)}\n")
        sb.append("系统: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) / ${Build.MANUFACTURER} ${Build.MODEL}\n")
        sb.append("详细报文: ${if (verbose) "开" else "关"}\n")
        sb.append("日志行数: ${lines.size}\n")
        sb.append("\n")
        sb.append("连接次数: $connects\n")
        sb.append("OutputCmd 总数: $cmds\n")
        sb.append("含 Error 的行: $errors\n")
        if (codes.isNotEmpty()) {
            sb.append("断开码分布: " + codes.entries.joinToString("  ") { "${it.key}×${it.value}" } + "\n")
            sb.append("  1000=客户端正常关闭  1001=端点离开  1006=异常断开(超时/网络断)\n")
        } else {
            sb.append("断开记录: 无\n")
        }
        if (sessions.isNotEmpty()) {
            sb.append("\n会话明细(最近 ${minOf(sessions.size, 20)} 段):\n")
            sessions.takeLast(20).forEach { sb.append(it).append("\n") }
        }
        return sb.toString()
    }

    private fun appVersion(context: Context): String = try {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        "${pi.versionName} (${pi.versionCode})"
    } catch (_: Exception) {
        "?"
    }
}
