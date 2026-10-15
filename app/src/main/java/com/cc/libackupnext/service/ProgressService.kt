package com.cc.libackupnext.service

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.content.IntentCompat
import com.cc.libackupnext.util.BackupNotifier
import com.cc.libackupnext.util.ErrorLog
import android.util.Log
import com.cc.libackupnext.util.KeepAwake

/**
 * 备份 / 恢复 / 删除期间的常驻前台服务。
 *
 * 让进程保持在前台，否则应用切到后台几秒后就会被系统冻结；
 * 通过 [KeepAwake] 持一把 Partial WakeLock，屏幕熄灭 / 进入 Doze 时 CPU 不被挂起。
 *
 * 服务结束时释放 WakeLock；整理仓库这类不走服务的操作由调用方自行 acquire / release。
 */
class ProgressService : Service() {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.getStringExtra(EXTRA_ACTION).orEmpty().ifBlank { "备份" }
        val notification = intent?.let {
            IntentCompat.getParcelableExtra(it, EXTRA_NOTIFICATION, Notification::class.java)
        } ?: BackupNotifier.buildInitial(this, action)

        // 备份 / 恢复 / 删除 都占着 CPU，结束后统一释放
        KeepAwake.acquire(this)

        startForeground(
            BackupNotifier.NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        // 被系统杀掉后自动重建：部分 ROM 会在一段时间后回收前台服务，
        // 服务没了就只能退回普通 notify，而普通 notify 在后台是被忽略的。
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        KeepAwake.release()

        if (instance === this) instance = null
        super.onDestroy()
    }

    /**
     * 用前台服务通道更新通知。
     *
     * 应用在后台时，直接 `NotificationManager.notify()` 更新「前台服务通知」在部分 ROM 上
     * 会被忽略（系统只认 [startForeground] 的更新），通知就卡在最后一次刷新的状态。
     */
    fun updateNotification(notification: Notification) {
        runCatching {
            startForeground(
                BackupNotifier.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        }.onFailure {
            // 后台更新失败会被静默吞掉，留一条记录方便排查
            Log.w("ProgressService", "startForeground update failed", it)
            if (!reportedUpdateFailure) {
                reportedUpdateFailure = true
                ErrorLog.add("通知", "更新前台通知失败", it)
            }
        }
    }

    private var reportedUpdateFailure = false

    /** 结束：退出前台但把「完成」通知留在通知栏，交给 BackupNotifier 稍后收掉。 */
    fun finishAndStop() {
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    companion object {
        const val EXTRA_ACTION = "action_label"
        const val EXTRA_NOTIFICATION = "initial_notification"

        /** 只在主进程里用，用来在结束时平滑退出前台。 */
        var instance: ProgressService? = null
            private set
    }
}
