package com.cc.libackupnext

import android.app.Application
import com.cc.libackupnext.util.AppLog

/**
 * @author cong
 * @date 2026/10/18
 */
class App: Application() {

    companion object {
        lateinit var application: App
            private set
    }

    override fun onCreate() {
        super.onCreate()
        application = this
        // 越早装越好：主进程和 :root 进程都会走这里，两边都要能落盘
        AppLog.install(this)
    }

}
