package com.cc.libackupnext

import android.app.Application
import com.cc.libackupnext.service.di.AppContainer
import com.cc.libackupnext.service.display.DefaultScreenOnSetting
import com.cc.libackupnext.ui.theme.ThemeSettings
import com.cc.libackupnext.util.AppLog

class App: Application() {

    companion object {
        lateinit var application: App
            private set
    }

    /**
     * 依赖容器：所有 service 层实现的唯一来源。
     *
     * `lazy` 而不是在 onCreate 里建：`:root` 进程不会实例化 Application，
     * 那边也不该碰容器；主进程第一次取用时才建，启动路径不变。
     */
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        application = this
        // 越早装越好：主进程和 :root 进程都会走这里，两边都要能落盘。
        // 配置读取口只有主进程有（:root 不实例化 Application，走的是各 Service 的 install）
        AppLog.install(
            context = this,
            app = this,
            verbose = { container.viewPrefs.verboseLog },
            repo = { container.repoDisplay }
        )
        // 主题状态是应用级单例，依赖在这里注入一次（:root 进程不实例化 Application，不会走到）
        ThemeSettings.install(container.viewPrefs)
        DefaultScreenOnSetting.install(container.backupPrefs)
    }

}
