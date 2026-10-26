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
 * 常驻前台服务 —— 为了「别让系统把进程冻住」, 同时兼作状态显示。
 *
 * ── 为什么需要它 ──────────────────────────────────────────────────────
 * Android 12 起会冻结后台应用进程(Freezer)。被冻结时进程不跑代码,
 * WebSocket 读写线程停摆, 对端看到的就是 Connection Reset。
 * 只有前台服务(带常驻通知)能把进程标成"用户可见的在用"。
 *
 * ── 通知为什么要塞内容 ────────────────────────────────────────────────
 * 只挂一句写死的话有两个问题:
 *   1. 用户看不出当前到底连没连上, 得切回 App
 *   2. 国产 ROM 对"没有信息量的前台服务通知"更容易折叠/清理
 * 所以通知现在实时反映: 监听地址、客户端是否在线、上次断开的原因。
 */
class BridgeForegroundService : Service() {

    companion object {
        /** 服务当前是否活着。用来发现"保活被系统清掉"并补一次。 */
        @Volatile
        var running: Boolean = false
            private set

        // v2: 旧的 channel 是 IMPORTANCE_LOW, 建好之后改不了等级, 只能换 id
        const val CHANNEL_ID = "fn010_bridge_v2"
        const val NOTI_ID = 1001
        const val ACTION_START = "com.yangcoiner.fn010bridge.START"
        const val ACTION_STOP = "com.yangcoiner.fn010bridge.STOP"

        /** 宿主的 applicationContext, 供 ButtplugServer 等直接更新通知。 */
        @Volatile
        private var appCtx: Context? = null

        @Volatile
        private var lastTitle = "FN010 Bridge 运行中"
        @Volatile
        private var lastDetail = "正在等待播放器连接"

        fun start(ctx: Context) {
            appCtx = ctx.applicationContext
            val i = Intent(ctx, BridgeForegroundService::class.java)
            i.action = ACTION_START
            ensureChannel(ctx)
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

        /**
         * 更新常驻通知。服务器连上/断开、地址变化都调它。
         * 用 applicationContext, 不依赖服务实例是否还活着 ——
         * 就算服务被系统清过一次, 这条通知也能立刻把状态刷新出来。
         */
        fun update(title: String, detail: String) {
            lastTitle = title
            lastDetail = detail
            val ctx = appCtx ?: return
            try {
                ensureChannel(ctx)
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTI_ID, build(ctx, title, detail))
            } catch (e: Exception) {
                LogHelper.log("SVC", "通知更新失败: ${e.javaClass.simpleName} ${e.message}")
            }
        }

        fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            val ch = NotificationChannel(
                CHANNEL_ID, "桥接服务状态", NotificationManager.IMPORTANCE_DEFAULT
            )
            ch.description = "显示连接地址与客户端在线状态"
            ch.setShowBadge(false)
            ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            nm.createNotificationChannel(ch)
        }

        fun build(ctx: Context, title: String, detail: String): Notification {
            val open = PendingIntent.getActivity(
                ctx, 0,
                Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val stopIntent = Intent(ctx, BridgeForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            val stopPi = PendingIntent.getService(
                ctx, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(ctx, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(ctx)
            }
            return builder
                .setContentTitle(title)
                .setContentText(detail)
                .setStyle(Notification.BigTextStyle().bigText(detail))
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(open)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        appCtx = applicationContext
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            LogHelper.log("SVC", "通知栏点了「停止」")
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForeground(NOTI_ID, build(this, lastTitle, lastDetail))
            running = true
            LogHelper.log("SVC", "前台服务已启动(通知可见): $lastDetail")
        } catch (e: Exception) {
            running = false
            LogHelper.log("SVC", "前台服务启动失败: ${e.javaClass.simpleName} ${e.message}")
            try {
                stopForeground(true)
            } catch (_: Exception) {
            }
        }
        MainActivity.acquireLocks(applicationContext) { /* 服务里静默失败即可 */ }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
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
}
