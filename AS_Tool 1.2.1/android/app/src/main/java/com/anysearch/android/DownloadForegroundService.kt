package com.anysearch.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * 下载前台服务（v1.2.0）：下载/批量下载运行时保持前台 + 常驻通知，
 * 切后台不中断；下载全部结束后停止服务并清除通知。
 */
class DownloadForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        ensureChannel(this)
        startForeground(NOTIF_ID, buildNotification(this, "准备下载…", 0, null))
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL_ID = "anysearch_download"
        private const val NOTIF_ID = 1001
        private const val ACTION_STOP = "stop"

        @Volatile
        private var running = false

        fun isRunning(): Boolean = running

        /** 启动前台服务（幂等） */
        fun start(context: Context) {
            if (running) return
            running = true
            ensureChannel(context)
            val intent = Intent(context, DownloadForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** 更新通知进度（节流由调用方负责） */
        fun update(context: Context, text: String, done: Long, total: Long?) {
            if (!running) return
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            nm.notify(NOTIF_ID, buildNotification(context, text, done, total))
        }

        /** 停止服务并清除通知（幂等） */
        fun stop(context: Context) {
            if (!running) return
            running = false
            try {
                val intent = Intent(context, DownloadForegroundService::class.java).setAction(ACTION_STOP)
                context.startService(intent)
            } catch (e: Exception) {
                // 忽略：应用进程可能已退出
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            nm.cancel(NOTIF_ID)
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
                if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                    nm.createNotificationChannel(
                        NotificationChannel(
                            CHANNEL_ID,
                            "下载任务",
                            NotificationManager.IMPORTANCE_LOW,
                        ).apply { description = "下载/批量下载进行中" },
                    )
                }
            }
        }

        private fun buildNotification(
            context: Context,
            text: String,
            done: Long,
            total: Long?,
        ): Notification {
            val contentIntent = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("AnySearch 工具")
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
            if (total != null && total > 0) {
                val pct = ((done.toDouble() / total) * 100).toInt().coerceIn(0, 100)
                builder.setProgress(100, pct, false)
            } else {
                builder.setProgress(0, 0, true)
            }
            return builder.build()
        }
    }
}
