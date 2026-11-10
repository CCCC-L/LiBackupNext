package com.cc.libackupnext.service

import com.cc.libackupnext.data.RemoteRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 主进程侧的 rclone 业务入口：目录浏览 / 新建文件夹，以及远程 restic 服务的地址。
 *
 * UI 只把 [RemoteRepo] 或本地路径交给这里，不接触 librclone.so 路径、inline 配置
 * 和帧协议。远程仓库的 URL 由 [ensureServe] 临时取回，不写进 AppPrefs。
 */
object RcloneClient {

    private const val TAG = "Rclone"

    private val manager by lazy {
        RootServiceManager<IRcloneService>(RcloneServer.Service::class.java) {
            IRcloneService.Stub.asInterface(it)
        }
    }

    private suspend fun getService(): IRcloneService = manager.getService()

    /**
     * 列一层子目录：本地 / 远程共用同一条 AIDL + rclone RC 通道。
     *
     * inline 配置随本次请求下传，不依赖已保存的仓库，所以初始化之前也能浏览。
     * create 不为空时先在该层建一个文件夹再重新列；fixup 是本地外部存储里那个
     * 新建目录的绝对路径，由 root 侧在成功后修正归属。
     */
    private suspend fun listDirs(
        fs: String,
        path: String,
        create: String?,
        fixupPath: String? = null
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val request = JSONObject().apply {
                put("lib", RemoteBackend.libraryPath())
                put("fs", fs)
                put("path", path)
                if (create != null) put("create", create)
                if (fixupPath != null) put("fixup", fixupPath)
            }
            val response = JSONObject(getService().listDirs(request.toString()))
            if (!response.optBoolean("ok", false)) {
                error(response.optString("error").ifBlank { "列目录失败" })
            }
            val dirs = response.optJSONArray("dirs") ?: JSONArray()
            (0 until dirs.length()).map { dirs.getString(it) }
        }
    }

    /**
     * 列出本地某一层下的子目录，供通用文件夹选择弹窗用。
     *
     * 新建的目录由 :root 进程（uid 0）建出来，在外部存储的 FUSE 视图下
     * 归属不对的话普通用户删不掉，所以把要修的绝对路径随请求交给 root 侧，
     * 由它在列目录成功后用 fixupTree 交还给 media_rw。
     */
    suspend fun listLocalDirs(
        path: String,
        create: String? = null
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        listDirs(RemoteBackend.localFs(), path, create, fixupTarget(path, create))
    }

    /** 远程路径浏览 / 测试连接：从服务根开始，不修本地归属。 */
    suspend fun listRemoteDirs(
        repo: RemoteRepo,
        path: String,
        create: String? = null
    ): Result<List<String>> = listDirs(RemoteBackend.browseFs(repo), path, create)

    /** 测试连接：能列出服务根目录就认为服务可达、账号密码正确。 */
    suspend fun testRemoteConnection(repo: RemoteRepo): Result<Unit> =
        listRemoteDirs(repo, "/").map { }

    /**
     * 只有落在已挂载卷（内部存储 / SD卡 / 外置存储）里的新建目录才修归属，
     * 避免误改 /data 等系统目录；远程路径从来不带 fixup。
     *
     * fixupTree 会把路径直接拼进 shell 脚本且不转义，所以整个 target 都要挡住
     * shell 元字符（path 里也可能有历史目录名），名字本身另外挡路径穿越。
     */
    private fun fixupTarget(path: String, name: String?): String? {
        if (name == null) return null
        if (name.isBlank() || name == ".." || name.contains('/') || name.contains('\\')) return null
        val inVolume = LocalVolumes.read(false, false).any { vol ->
            vol.path != "/" && (path == vol.path || path.startsWith("${vol.path}/"))
        }
        if (!inVolume) return null
        val target = "${path.trimEnd('/')}/$name"
        if (target.any { it == '"' || it == '$' || it == '`' || it == '\n' || it == '\r' }) return null
        return target
    }

    /**
     * 起（或复用）一个 serve restic，返回给 rustic RestBackend 用的 URL。
     *
     * URL 里带本次随机生成的 basic-auth token 与端口，只在当前 root 进程内有效：
     * 调用方把它放进本次请求的 options，绝不写进 AppPrefs。key 带上 inline 配置，
     * 配置一变 root 侧就会停掉旧服务再起新的。
     */
    suspend fun ensureServe(repo: RemoteRepo): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val fs = RemoteBackend.serveFs(repo)
            val request = JSONObject().apply {
                put("lib", RemoteBackend.libraryPath())
                put("fs", fs)
                put("key", "${repo.location}|$fs")
            }
            val response = JSONObject(getService().ensureServe(request.toString()))
            if (!response.optBoolean("ok", false)) {
                error(response.optString("error").ifBlank { "启动远程服务失败" })
            }
            response.getString("url")
        }
    }

    /** 手动停掉当前的 serve restic（配置切换一般由 key 变化自动完成）。 */
    suspend fun stopServe(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val response = JSONObject(getService().stopServe())
            if (!response.optBoolean("ok", false)) {
                error(response.optString("error").ifBlank { "停止远程服务失败" })
            }
            Unit
        }
    }
}
