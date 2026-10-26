package com.yangcoiner.fn010bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * 常驻前台服务 —— 只是为了「别让系统把进程冻住」。
 *
 * ── 为什么需要它 ──────────────────────────────────────────────────────
 * Android 从 12 起会冻结后台应用进程(Freezer)。被冻结时:
 *   * 进程不跑代码, WebSocket 的读写线程停摆
 *   * TCP 层收不到 ACK, 对端看到的就是 Connection Reset
 *   * 唤醒锁能阻止 CPU 休眠, 但**阻止不了进程被冻**
 * 只有前台服务(带常驻通知)能把进程标成"用户可见的在用", 免于冻结。
 *
 * 服务器本身还是跑在 MainActivity 的进程级静态字段里, 这个服务不持有它,
 * 只负责:
 *   1. 把进程钉住
 *   2. 顺带持有 Wi-Fi / CPU 锁
 *   3. 用户点通知能回到界面
 */
class BridgeForegroundService : Service() {

    companion object {
        /** 服务当前是否活着。用来发现"保活被系统清掉了"并补一次。 */
        @Volatile
        var running: Boolean = false

        const val CHANNEL_ID = "fn010_bridge"
        const val NOTI_ID = 1001
        const val ACTION_START = "com.yangcoiner.fn010bridge.START"
        const val ACTION_STOP = "com.yangcoiner.fn010bridge.STOP"

        fun start(ctx: Context) {
            val i = Intent(ctx, BridgeForegroundService::class.java)
            i.action = ACTION_START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, BridgeForegroundService::class.java)
            i.action = ACTION_STOP
            try {
                ctx.startService(i)
            } catch (_: Exception) {
                ctx.stopService(Intent(ctx, BridgeForegroundService::class.java))
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForeground(NOTI_ID, buildNotification())
            running = true
            LogHelper.log("SVC", "前台服务已启动(常驻通知已挂)")
        } catch (e: Exception) {
            running = false
            LogHelper.log("SVC", "前台服务启动失败: ${e.javaClass.simpleName} ${e.message}")
            // Android 14 对 connectedDevice 类型要求蓝牙权限已授权;
            // 权限没给时 startForeground 会抛 SecurityException。
            // 这种情况下不要崩, 退化成普通后台服务(Wi-Fi/CPU 锁仍然有效)。
            try {
                stopForeground(true)
            } catch (_: Exception) {
            }
        }
        // 双保险: 前台服务负责"不被冻结", 锁负责"CPU/Wi-Fi 不休眠"
        MainActivity.acquireLocks(applicationContext) { /* 服务里静默失败即可 */ }
        // 被系统杀掉后自动重建, 比 START_STICKY 更可靠地保持常驻
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 用户在最近任务里划掉时不要把服务带走, 否则连接必断
        LogHelper.log("SVC", "任务被划掉, 服务保持运行")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        running = false
        LogHelper.log("SVC", "前台服务已销毁")
        try {
            stopForeground(true)
        } catch (_: Exception) {
        }
        MainActivity.releaseLocks()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID, "桥接服务", NotificationManager.IMPORTANCE_LOW
                )
                ch.description = "保持 WebSocket 服务器常驻"
                ch.setShowBadge(false)
                nm.createNotificationChannel(ch)
            }
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("FN010 Bridge 运行中")
            .setContentText("WebSocket 服务器常驻, 熄屏不断连")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }
}
