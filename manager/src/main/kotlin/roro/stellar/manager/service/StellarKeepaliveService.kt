package roro.stellar.manager.service

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import roro.stellar.manager.AppConstants
import roro.stellar.manager.MainActivity
import roro.stellar.manager.R
import roro.stellar.manager.compat.BuildUtils.atLeast26
import roro.stellar.manager.compat.BuildUtils.atLeast29
import roro.stellar.manager.compat.BuildUtils.atLeast31

/**
 * 常驻前台服务。
 *
 * 管理器进程承载 StellarManagerProvider，其它应用通过它取 Binder。该进程平时没有前台组件，
 * 在内存回收时被 lmkd 判定为 importance=400 的普通后台进程反复清理，进程一死，所有已经
 * 取到 Binder 的客户端连接随之断开。挂一个前台服务把进程固定到 importance=100 附近，
 * 才能让 Binder 会话在网络切换之类的时机保持存活。
 */
class StellarKeepaliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        return START_STICKY
    }

    private fun startForegroundCompat() {
        ensureChannel()
        val notification = buildNotification()

        if (atLeast29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stellar)
            .setContentTitle(getString(R.string.keepalive_notification_title))
            .setContentText(getString(R.string.keepalive_notification_description))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun ensureChannel() {
        if (!atLeast26) return

        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.keepalive_channel_name),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = getString(R.string.keepalive_channel_description)
            setShowBadge(false)
            setSound(null, null)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = AppConstants.TAG
        const val CHANNEL_ID = "stellar_keepalive"
        const val NOTIFICATION_ID = 1446

        /**
         * 确保常驻服务已运行。
         *
         * Android 12 起禁止后台进程启动前台服务：Provider 被其它应用拉起的场景下，
         * 进程既非前台也不在允许清单里，直接 startForegroundService 会抛
         * ForegroundServiceStartNotAllowedException。这里先判断进程自身的 importance，
         * 只有已经处于前台/可见/前台服务状态时才启动，其余情况交给用户打开界面或
         * 下次开机广播处理。
         */
        fun ensureRunning(context: Context) {
            if (isRunning(context)) return

            if (atLeast31 && !mayStartForeground(context)) {
                Log.i(TAG, "进程当前非前台，跳过常驻服务启动")
                return
            }

            try {
                context.startForegroundService(Intent(context, StellarKeepaliveService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "启动常驻服务失败", e)
            }
        }

        fun isRunning(context: Context): Boolean {
            val am = context.getSystemService(ActivityManager::class.java) ?: return false
            return am.getRunningServices(Int.MAX_VALUE).any {
                it.service.className == StellarKeepaliveService::class.java.name
            }
        }

        private fun mayStartForeground(context: Context): Boolean {
            val am = context.getSystemService(ActivityManager::class.java) ?: return false
            val importance = am.runningAppProcesses
                ?.firstOrNull { it.pid == android.os.Process.myPid() }
                ?.importance
                ?: return false

            return importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, StellarKeepaliveService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "停止常驻服务失败", e)
            }
        }
    }
}
