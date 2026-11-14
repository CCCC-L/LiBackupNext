package com.cc.libackupnext.service

import com.cc.libackupnext.App
import com.cc.libackupnext.data.RemoteRepo
import com.cc.libackupnext.data.RepoAuth
import com.cc.libackupnext.data.RepoProtocol
import com.cc.libackupnext.data.json.BackendOptions
import com.cc.libackupnext.data.json.LocalFsConfig
import com.cc.libackupnext.data.json.RcloneFsConfig
import com.cc.libackupnext.service.json.toJsonText
import java.io.File

/**
 * 目录浏览 / 仓库位置 → librclone / rustic 请求参数的转换。
 *
 * 只有这里能碰 librclone.so 的加载路径与 rclone 的 inline 配置；
 * [RcloneClient] 用它们拼请求，UI 只把 [RemoteRepo] 交给业务层，不接触这些字符串。
 * 本地目录浏览也走 rclone 的 local 后端，所以配置同样在这里生成。
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
    fun localFs(): String = LocalFsConfig(type = "local", root = "/").toJsonText()

    /**
     * rustic_backend 的 options：remote: 前缀由 Rust 侧换成 rest:<url>。
     *
     * [restUrl] 是 [RcloneClient.ensureServe] 本次会话临时取回的地址，含随机
     * token 和端口，只在当前 root 进程内有效，绝不写进 AppPrefs。
     */
    fun optionsFor(restUrl: String): String = BackendOptions(restUrl).toJsonText()

    /** serve restic 用的 inline 配置：仓库路径作为起始目录，不是从服务根开始。 */
    fun serveFs(repo: RemoteRepo): String = fsJson(repo, repo.path.ifBlank { "/" })

    /** 列目录 / 测试连接：从服务根开始浏览，而不是只看仓库目录里面。 */
    fun browseFs(repo: RemoteRepo): String = fsJson(repo, "/")

    /**
     * rclone 的 inline 配置（JSON 字符串）。密码明文只在这一层出现，
     * root 侧 Rust 会转成 rclone 要求的混淆形式；配置不落盘、不进日志。
     *
     * 可选字段一律留 null —— rclone 遇到未知 / 多余的 option 会直接报错。
     *
     * @param root 服务内的起始路径：仓库读写用 repo.path，目录浏览用 "/"。
     */
    private fun fsJson(repo: RemoteRepo, root: String): String {
        var config = RcloneFsConfig(type = repo.protocol.key, root = root)
        val effective = repo.effectiveAuth()
        when (repo.protocol) {
            // 本地仓库直接走 LocalBackend，不会生成 fsJson；这里只为补齐 when 分支
            RepoProtocol.LOCAL -> Unit

            RepoProtocol.WEBDAV -> {
                val host = repo.host.trim().trimEnd('/')
                val scheme = if (repo.insecure) "http" else "https"
                config = config.copy(
                    url = if (host.contains("://")) host else "$scheme://$host",
                    user = repo.user,
                    pass = repo.password,
                    // 本项目自定义键：Rust 侧会把它从 inline 配置里剥离，转成 RC 顶层的
                    // no_check_certificate；rclone 的 webdav backend 本身没有这个 option。
                    insecure = true.takeIf { repo.insecure }
                )
            }

            RepoProtocol.SFTP -> {
                val (h, p) = splitHostPort(repo.protocol, repo.host)
                config = config.copy(
                    host = h,
                    port = p,
                    user = repo.user,
                    keyPem = if (effective == RepoAuth.PUBLIC_KEY) {
                        singleLinePem(repo.privateKey)
                    } else null,
                    pass = if (effective == RepoAuth.PUBLIC_KEY) null else repo.password
                )
            }

            RepoProtocol.SMB -> {
                // rclone 的 smb 后端没有 share 配置项：共享名取自 _root 的第一段，
                // 也就是用户填的远程路径第一段；浏览根目录时会列出所有共享。
                val (h, p) = splitHostPort(repo.protocol, repo.host)
                config = config.copy(host = h, port = p)
                config = when (effective) {
                    RepoAuth.PASSWORD -> config.copy(user = repo.user, pass = repo.password)
                    // guest 走空密码会话；anonymous 不填 user，rclone 会替换成进程
                    // 当前用户名，能否连上取决于服务器是否接受这种空凭据会话。
                    RepoAuth.GUEST -> config.copy(user = "guest")
                    RepoAuth.ANONYMOUS -> config.copy(user = "")
                    RepoAuth.PUBLIC_KEY -> config
                }
            }
        }
        return config.toJsonText()
    }

    /** rclone 的 key_pem 要求单行、换行写成字面 \n，内部再 Unquote 还原成真换行。 */
    private fun singleLinePem(value: String): String = value.replace("\r\n", "\n")
        .replace('\r', '\n')
        .trim()
        .replace("\n", "\\n")

    private fun splitHostPort(
        protocol: RepoProtocol,
        value: String
    ): Pair<String, String> {
        val raw = value.substringAfter("://").substringBefore('/')
        val port = raw.substringAfterLast(':', "")
        val hostOnly = if (port.isBlank()) raw else raw.substringBeforeLast(':')
        val defaultPort = if (protocol == RepoProtocol.SFTP) "22" else "445"
        return hostOnly to port.ifBlank { defaultPort }
    }
}
