package com.yangcoiner.fn010bridge

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 轻量 debug 日志:内存环形缓冲 + 追加写文件。
 * 文件位置:Android/data/com.yangcoiner.fn010bridge/files/logs/fn010_log.txt
 * 通过「导出日志」按钮分享导出。
 */
object LogHelper {
    private const val MAX_LINES = 3000
    private val buf = ArrayDeque<String>()
    private var logFile: File? = null
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

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
        try {
            logFile?.appendText(line + "\n")
        } catch (_: Exception) {}
    }

    @Synchronized
    fun dump(): String = buf.joinToString("\n")
}
