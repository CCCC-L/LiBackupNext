package com.cc.libackupnext.data.protocol

import com.cc.libackupnext.data.RemoteRepo

/**
 * SMB：地址是「主机:端口」，端口缺省 445，支持密码 / 来宾 / 匿名三种登录。
 *
 * rclone 的 smb 后端没有 share 配置项：共享名取自 _root 的第一段，
 * 也就是用户填的远程路径第一段；浏览根目录时会列出所有共享。
 */
object SmbProtocol : RepoProtocol {

    override val key: String = "smb"

    override val label: String = "SMB"

    override val auths: List<RepoAuthOption> = listOf(
        RepoAuthOption("password", "密码"),
        RepoAuthOption("guest", "来宾"),
        RepoAuthOption("anonymous", "匿名")
    )

    private const val USER = "user"
    private const val PASS = "password"
    private const val AUTH_PASSWORD = "password"
    private const val AUTH_GUEST = "guest"
    private const val AUTH_ANONYMOUS = "anonymous"

    override fun fields(repo: RemoteRepo): List<RepoField> = buildList {
        add(RepoField.Text(FIELD_HOST, "地址（地址:端口）", "192.168.1.10:445", required = true))
        add(RepoField.Select(FIELD_AUTH, "认证方式", auths))
        if (effectiveAuth(repo)?.key == AUTH_PASSWORD) {
            add(RepoField.Text(USER, "用户名", "用户名", required = true))
            add(RepoField.Text(PASS, "服务密码", secret = true, required = true))
        }
    }

    override fun rcloneOptions(repo: RemoteRepo): Map<String, String> {
        val (host, port) = splitHostPort(repo[FIELD_HOST], "445")
        return buildMap {
            put("host", host)
            put("port", port)
            when (effectiveAuth(repo)?.key) {
                AUTH_PASSWORD -> {
                    put("user", repo[USER])
                    put("pass", repo[PASS])
                }
                // guest 走空密码会话
                AUTH_GUEST -> put("user", "guest")
                // anonymous 不填 user，rclone 会替换成进程当前用户名，
                // 能否连上取决于服务器是否接受这种空凭据会话
                AUTH_ANONYMOUS -> put("user", "")
                else -> Unit
            }
        }
    }

    override fun address(repo: RemoteRepo): String =
        withPath("smb://${stripScheme(repo[FIELD_HOST])}", repo)
}
