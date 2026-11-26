package com.cc.libackupnext.service.gateway

import android.app.Application
import android.os.IBinder
import com.cc.libackupnext.data.prefs.RepoPrefs
import com.cc.libackupnext.service.IRusticService
import com.cc.libackupnext.service.rclone.RcloneClient
import com.cc.libackupnext.service.rclone.RemoteBackend
import com.cc.libackupnext.service.root.RootServiceManager
import com.cc.libackupnext.service.root.RusticServer
import com.cc.libackupnext.util.AppLog
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 与 root 侧 rustic 服务的连接状态：绑定、凭据注入、错误归一化。
 *
 * 凭据模型：仓库地址和密码只由 [service] 注入 root 侧，业务请求本身不带凭据。
 * 候选仓库校验（[binder]）刻意不注入当前凭据 —— 免得旧仓库连不上时
 * 把刚起好的候选 serve 顶掉。
 */
internal class RusticSession(
    private val app: Application,
    private val repoPrefs: RepoPrefs,
) {

    companion object {
        const val TAG = "Rustic"

        /** 不含凭据的空请求：仓库地址和密码只由 [service] 注入。 */
        const val EMPTY_REQUEST = "{}"
    }

    private val manager by lazy {
        RootServiceManager<IRusticService>(RusticServer.Service::class.java) {
            IRusticService.Stub.asInterface(it)
        }
    }

    /** 错误归一化：断连判定、软错误收敛、只读调用的一次性重放。 */
    val remote = RemoteCallHandler(
        isRemote = { repoPrefs.isRemote },
        onCredentialsStale = { invalidateCredentials() },
    )

    private val credentialLock = Mutex()

    @Volatile
    private var credentialVersion = 0L
    private var injectedBinder: IBinder? = null
    private var injectedVersion = -1L

    /** 自身 Application：取 cacheDir / 自身 APK 路径用，不读全局单例。 */
    val application: Application get() = app

    val repoPath: String get() = repoPrefs.location
    val repoPassword: String get() = repoPrefs.password
    val repoOptions: String get() = repoPrefs.options

    /** 凭据需要重新对齐（初始化成功 / 服务重启）：下次调用重新注入真源。 */
    fun invalidateCredentials() {
        credentialVersion++
    }

    /** 只绑定 root 服务：候选仓库校验、不碰仓库的本机操作都不需要注入当前凭据。 */
    suspend fun binder(): IRusticService = manager.getService()

    /**
     * 绑定并按需注入凭据；所有访问仓库的 rustic 命令的唯一出口。
     *
     * 不在每笔请求前测连：远端不可达会由第一笔真实请求失败暴露，再经
     * [RemoteCallHandler.call] 统一处理。
     */
    suspend fun service(): IRusticService {
        // 逻辑标识 -> 远程配置的解析收在 RepoPrefs 里，这里不碰字符串格式
        val remoteRepo = if (repoPrefs.isRemote) repoPrefs.remoteConfigFor(repoPath) else null

        val service = binder()
        credentialLock.withLock {
            val binder = service.asBinder()
            if (injectedBinder !== binder || injectedVersion != credentialVersion) {
                // 只记「注入了凭据」，绝不能把密码写进日志
                AppLog.i(TAG, "绑定 root 服务，注入仓库凭据")
                // rest_url 由 rclone 临时起 serve 取回，含随机 token，绝不落盘。
                val options = if (remoteRepo != null) {
                    // serve 起不来算传输层失败（后面每笔都会失败，必须停批）；
                    // 文案已由 RcloneClient 归好类，这里只搬过来。
                    val url = RcloneClient.ensureServe(remoteRepo).getOrElse { error ->
                        throw RemoteServeException(
                            message = error.message?.takeIf { it.isNotBlank() }
                                ?: "启动远程服务失败",
                            detail = (error as? BackendFailureException)?.detail,
                            cause = error
                        )
                    }
                    RemoteBackend.optionsFor(url)
                } else repoOptions
                service.setRepo(repoPath, repoPassword, options)
                injectedBinder = binder
                injectedVersion = credentialVersion
            }
        }
        return service
    }
}
