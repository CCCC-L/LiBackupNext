package com.cc.libackupnext.util

import android.content.Context
import android.os.PowerManager
import android.util.Log

/**
 * 操作期间的 CPU 保持：备份 / 恢复 / 删除 / 整理仓库时持有 Partial WakeLock，
 * 避免屏幕熄灭或进入 Doze 后任务被挂起。
 *
 * 支持多个持有者（前台服务 + 页面里的单次操作），内部按计数管理，归零才真正释放。
 */
object KeepAwake {

    private const val TAG = "KeepAwake"
    private const val WAKE_LOCK_TAG = "LiBackupNext:operation"

    /** 单个操作最长占用 3 小时，防止异常情况下一直不放。 */
    private const val TIMEOUT_MS = 3 * 60 * 60 * 1000L

    private var lock: PowerManager.WakeLock? = null
    private var holders = 0

    @Synchronized
    fun acquire(context: Context) {
        holders++
        if (lock?.isHeld == true) return

        lock = runCatching {
            context.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                .apply {
                    setReferenceCounted(false)
                    acquire(TIMEOUT_MS)
                }
        }.getOrNull()

        if (lock == null) {
            Log.w(TAG, "acquire wake lock failed")
            holders = 0
        }
    }

    @Synchronized
    fun release() {
        if (holders > 0) holders--
        if (holders > 0) return

        runCatching { lock?.takeIf { it.isHeld }?.release() }
        lock = null
    }
}
