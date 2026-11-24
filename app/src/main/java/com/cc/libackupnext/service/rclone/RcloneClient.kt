package com.cc.libackupnext.service.rclone

import com.cc.libackupnext.data.MediaPath
import com.cc.libackupnext.data.RemoteRepo
import com.cc.libackupnext.data.json.EnsureServeRequest
import com.cc.libackupnext.data.json.EnsureServeResponse
import com.cc.libackupnext.data.json.ListDirsRequest
import com.cc.libackupnext.data.json.ListDirsResponse
import com.cc.libackupnext.data.json.OkResponse
import com.cc.libackupnext.service.gateway.DirectoryBrowser
import com.cc.libackupnext.service.json.parseJson
import com.cc.libackupnext.service.json.toJsonText
import com.cc.libackupnext.service.gateway.BackendMessage
import com.cc.libackupnext.service.gateway.BackendFailureException
import com.cc.libackupnext.service.gateway.BackendRawException
import com.cc.libackupnext.service.gateway.BackendSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.cc.libackupnext.service.IRcloneService
import com.cc.libackupnext.service.root.RcloneServer
import com.cc.libackupnext.service.root.RootServiceManager
import com.cc.libackupnext.service.browse.LocalVolumes
import com.cc.libackupnext.util.AppLog

/**
 * 主进程侧的 rclone 业务入口：目录浏览 / 新建文件夹，以及远程 restic 服务的地址。
 *
 * UI 只把 [RemoteRepo] 或本地路径交给这里，不接触 librclone.so 路径、inline 配置
 * 和帧协议。远程仓库的 URL 由 [ensureServe] 临时取回，不写进 RepoPrefs。
 */
object RcloneClient : DirectoryBrowser {

    private const val TAG = "Rclone"

    private val manager by lazy {
        RootServiceManager<IRcloneService>(RcloneServer.Service::class.java) {
            IRcloneService.Stub.asInterface(it)
        }
    }

    private suspend fun getService(): IRcloneService = manager.getService()

    /**
     * 所有 AIDL 调用的唯一出口：完整响应写日志（rclone 先脱敏），
     * 失败时按 rclone 的报错特征归类（认证失败 / 连不上 / 超时 / 路径不存在），
     * 认得出就给一句中文，认不出才把原文交给 UI。
     */
    private suspend fun call(
        label: String,
        invoke: (IRcloneService) -> String
    ): String = withContext(Dispatchers.IO) {
        val raw = invoke(getService())
        val envelope = runCatching { raw.parseJson<OkResponse>() }
            .getOrDefault(OkResponse(ok = false, error = raw))
        val brief = BackendMessage.record(
            source = BackendSource.RCLONE,
            raw = raw,
            error = envelope.error,
            ok = envelope.ok,
            label = label,
            reason = if (envelope.ok) {
                null
            } else {
                RcloneFailureClassifier.classify(envelope.error ?: raw)
            }
        )
        if (!envelope.ok) {
            throw BackendFailureException(
                message = brief.text.ifBlank { "远程操作失败" },
                detail = brief.detail,
                cause = BackendRawException(BackendSource.RCLONE, raw)
            )
        }
        raw
    }

    /**
     * 列一层子目录（本地 / 远程同一条通道）。inline 配置随请求下传，初始化前也能浏览。
     * create 非空时先建文件夹再重列；fixup 是新建目录的绝对路径，由 root 侧修正归属。
     */
    private suspend fun listDirs(
        fs: String,
        path: String,
        create: String?,
        fixupPath: String? = null
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val request = ListDirsRequest(
                lib = RemoteBackend.libraryPath(),
                fs = fs,
                path = path,
                create = create,
                fixup = fixupPath
            )
            val requestJson = request.toJsonText()
            val raw = call("listDirs path=$path create=${create != null}") {
                it.listDirs(requestJson)
            }
            raw.parseJson<ListDirsResponse>().dirs
        }
    }

    /**
     * 列出本地某一层下的子目录，供文件夹选择弹窗用。
     * :root（uid 0）建出来的目录在 FUSE 视图下归属不对，普通用户删不掉，
     * 所以把绝对路径交给 root 侧用 fixupTree 交还给 media_rw。
     */
    override suspend fun listLocalDirs(
        path: String,
        create: String?
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        listDirs(RemoteBackend.localFs(), path, create, fixupTarget(path, create))
    }

    /** 远程路径浏览 / 测试连接：从服务根开始，不修本地归属。 */
    override suspend fun listRemoteDirs(
        repo: RemoteRepo,
        path: String,
        create: String?
    ): Result<List<String>> = listDirs(RemoteBackend.browseFs(repo), path, create)

    /** 测试连接：能列出服务根目录就认为服务可达、账号密码正确。 */
    suspend fun testRemoteConnection(repo: RemoteRepo): Result<Unit> =
        listRemoteDirs(repo, "/").map { }

    /**
     * 只给已挂载卷里的新建目录修归属，避免误改 /data；远程路径从不带 fixup。
     * fixupTree 把路径直接拼进 shell 脚本，所以 target 必须挡住 shell 元字符，
     * 名字本身另挡路径穿越。
     */
    private fun fixupTarget(path: String, name: String?): String? {
        if (name == null) return null
        if (name.isBlank() || name == ".." || name.contains('/') || name.contains('\\')) return null
        // 模拟卷也算「卷」：看别的用户的空间时浏览走的是 /data/media/<id>，
        // 它不在挂载表里，但归属照样要按该用户的 MediaProvider 修
        val inVolume = MediaPath.userOf(path) != null || LocalVolumes.read(false, false).any { vol ->
            vol.path != "/" && (path == vol.path || path.startsWith("${vol.path}/"))
        }
        if (!inVolume) return null
        val target = "${path.trimEnd('/')}/$name"
        if (target.any { it == '"' || it == '$' || it == '`' || it == '\n' || it == '\r' }) return null
        return target
    }

    /**
     * 起（或复用）serve restic，返回给 rustic RestBackend 的 URL。
     * URL 带本次随机 token 与端口，只放进请求 options，绝不写进 RepoPrefs；
     * key 含 inline 配置，配置一变 root 侧就停旧起新。
     */
    suspend fun ensureServe(repo: RemoteRepo): Result<String> = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val result = runCatching {
            val fs = RemoteBackend.serveFs(repo)
            val request = EnsureServeRequest(
                lib = RemoteBackend.libraryPath(),
                fs = fs,
                key = "${repo.location}|$fs"
            )
            val raw = call("ensureServe") { it.ensureServe(request.toJsonText()) }
            raw.parseJson<EnsureServeResponse>().url?.takeIf { it.isNotBlank() }
                ?: error("启动远程服务失败")
        }
        // 绑定 :rclone 的耗时也算在里面：它和 serve/start 的 15s 是两段
        AppLog.i(
            TAG,
            "ensureServe 结束，总耗时 ${System.currentTimeMillis() - startedAt}ms，" +
                    "结果=${if (result.isSuccess) "成功" else "失败"}"
        )
        result
    }

    /** 手动停掉当前的 serve restic（配置切换一般由 key 变化自动完成）。 */
    suspend fun stopServe(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            call("stopServe") { it.stopServe() }
            Unit
        }
    }
}
