package com.cc.libackupnext.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

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
        Log.w(TAG, "isGranted error", t)
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
                Log.i(TAG, "mount-master enabled")
            } else {
                Log.w(TAG, "mount-master not supported, fallback to default namespace")
            }
        }.onFailure { Log.w(TAG, "mount-master probe failed, fallback", it) }
    }

    fun request(): Boolean = try {
        prepareShell()
        val result = Shell.cmd("id").exec()
        val ok = result.isSuccess && result.out.any { it.contains("uid=0") }
        Log.d(TAG, "request: isSuccess=${result.isSuccess}, ok=$ok")
        ok
    } catch (t: Throwable) {
        Log.e(TAG, "request error", t)
        false
    }

}
