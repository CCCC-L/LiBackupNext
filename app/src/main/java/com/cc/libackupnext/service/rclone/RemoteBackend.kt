package com.cc.libackupnext.service.rclone

import com.cc.libackupnext.App
import com.cc.libackupnext.data.RemoteRepo
import com.cc.libackupnext.data.json.BackendOptions
import com.cc.libackupnext.data.protocol.LocalProtocol
import com.cc.libackupnext.service.json.toJsonText
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * 目录浏览 / 仓库位置 → librclone / rustic 请求参数的转换。
 *
 * 只有这里能碰 librclone.so 的加载路径与 rclone 的 inline 配置；
 * 各协议的配置项由 data/protocol 里的协议文件给出（[com.cc.libackupnext.data.protocol.RepoProtocol.rcloneOptions]），
 * 这里只负责把 type / _root 与它们拼成 JSON，不再认识任何具体协议。
 * [RcloneClient] 用它们拼请求，UI 只把 [RemoteRepo] 交给业务层，不接触这些字符串。
 * rustic 侧的 options 只带 ensureServe 临时取回的 rest_url，不含 inline 配置。
 */
object RemoteBackend {

    /** :root 进程与主进程是同一个 APK，nativeLibraryDir 一致。 */
    fun libraryPath(): String =
        File(App.application.applicationInfo.nativeLibraryDir, "librclone.so").absolutePath

    /**
     * 本地目录浏览的 inline 配置：从文件系统根开始，拦一层路径交给 `operations/list`。
     *
     * rclone RC 会把 `_root` 从配置里取出来拼成 `:local:/`；请求里的 path 是绝对路径，
     * Rust 侧 trim 掉前导斜杠后正好是相对这个 root 的 remote。
     */
    fun localFs(): String = fsJson(LocalProtocol.key, "/", emptyMap())

    /**
     * rustic_backend 的 options：remote: 前缀由 Rust 侧换成 rest:<url>。
     *
     * [restUrl] 是 [RcloneClient.ensureServe] 本次会话临时取回的地址，含随机
     * token 和端口，只在当前 root 进程内有效，绝不写进 RepoPrefs。
     */
    fun optionsFor(restUrl: String): String = BackendOptions(restUrl).toJsonText()

    /** serve restic 用的 inline 配置：仓库路径作为起始目录，不是从服务根开始。 */
    fun serveFs(repo: RemoteRepo): String = fsJson(repo, repo.path.ifBlank { "/" })

    /** 列目录 / 测试连接：从服务根开始浏览，而不是只看仓库目录里面。 */
    fun browseFs(repo: RemoteRepo): String = fsJson(repo, "/")

    /**
     * 远程服务的 inline 配置（JSON 字符串）。密码明文只在这一层出现，
     * root 侧 Rust 会转成 rclone 要求的混淆形式；配置不落盘、不进日志。
     *
     * 可选字段由协议自己决定要不要下发 —— rclone 遇到未知 / 多余的 option 会直接报错，
     * 所以协议文件只 put 当前登录方式真正用到的键。
     *
     * @param root 服务内的起始路径：仓库读写用 repo.path，目录浏览用 "/"。
     */
    private fun fsJson(repo: RemoteRepo, root: String): String {
        check(!repo.protocol.isLocal) { "本地仓库不走远程配置" }
        return fsJson(repo.protocol.key, root, repo.protocol.rcloneOptions(repo))
    }

    private fun fsJson(type: String, root: String, options: Map<String, String>): String =
        buildJsonObject {
            put("type", type)
            put("_root", root)
            options.forEach { (key, value) -> put(key, value) }
        }.toJsonText()
}
