package com.cc.libackupnext.service.root

import android.util.Log
import com.cc.libackupnext.util.AppLog
import com.topjohnwu.superuser.Shell

object RootManager {

    private const val TAG = "RootManager"

    // ==================== 授权相关 ====================

    /**
     * 静默检查：当前进程是否已获得 Root（不会弹窗）。
     * 注意：首次启动时通常返回 false，即使设备已 root 也一样。
     */
    fun isGranted(): Boolean = try {
        Shell.isAppGrantedRoot() == true
    } catch (t: Throwable) {
        AppLog.w(TAG, "isGranted error", t)
        false
    }

    /**
     * 主动请求 Root 权限（阻塞直到用户响应）。
     * 用 Shell.cmd("id") 而不是 getShell().isRoot，
     * 因为 getShell() 是异步的，返回时 isRoot 未必准确。
     */
    @Volatile
    private var shellPrepared = false

    /**
     * root 服务要能看到 /data/user、/data/media 这些目录，
     * 需要让 su 跑在全局挂载命名空间（--mount-master）。
     * 先探测当前 root 实现是否支持，不支持就保持默认行为。
     */
    private fun prepareShell() {
        if (shellPrepared) return
        shellPrepared = true

        runCatching {
            val probe = Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                .setTimeout(10)
                .build()
            val supported = probe.isRoot
            probe.close()

            if (supported) {
                Shell.setDefaultBuilder(
                    Shell.Builder.create()
                        .setFlags(Shell.FLAG_MOUNT_MASTER)
                        .setTimeout(30)
                )
                Log.d(TAG, "mount-master enabled")
            } else {
                // 没有 mount-master 会看不到 /data，必须留痕
                AppLog.w(TAG, "mount-master not supported, fallback to default namespace")
            }
        }.onFailure { AppLog.w(TAG, "mount-master probe failed, fallback", it) }
    }

    fun request(): Boolean = try {
        prepareShell()
        val result = Shell.cmd("id").exec()
        result.isSuccess && result.out.any { it.contains("uid=0") }
    } catch (t: Throwable) {
        AppLog.e(TAG, "request error", t)
        false
    }

}
