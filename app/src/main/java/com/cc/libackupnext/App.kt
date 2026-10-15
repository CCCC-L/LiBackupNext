package com.cc.libackupnext

import android.app.Application
import com.cc.libackupnext.util.ErrorLog

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
        ErrorLog.installCrashHandler()
    }

}