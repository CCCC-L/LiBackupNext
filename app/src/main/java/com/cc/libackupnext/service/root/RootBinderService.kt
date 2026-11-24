package com.cc.libackupnext.service.root

import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.cc.libackupnext.util.AppLog
import com.topjohnwu.superuser.ipc.RootService

/**
 * 三个 root 服务（[AppManagerServer] / [RusticServer] / [RcloneServer]）共用的骨架。
 *
 * 只封 `RootService` 的两段样板：
 * - `onCreate`：libsu 不实例化 Application，`:root` 的日志只能在这里装（幂等）；
 * - `onBind`：binder 的构造交给子类。
 */
abstract class RootBinderService : RootService() {

    final override fun onCreate() {
        super.onCreate()
        AppLog.install(applicationContext)
        RootOomGuard.protect()
    }

    final override fun onBind(intent: Intent): IBinder = createBinder(applicationContext)

    /** 在 `:root` 进程里构造 AIDL 实现，可以假设 uid == 0。 */
    protected abstract fun createBinder(context: Context): IBinder
}
