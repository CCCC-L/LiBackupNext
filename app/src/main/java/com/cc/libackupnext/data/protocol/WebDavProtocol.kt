package com.cc.libackupnext.data.protocol

import com.cc.libackupnext.data.RemoteRepo

/**
 * WebDAV：一个 URL 就是服务地址，只有密码登录。
 *
 * [INSECURE] 是本项目自定义的键：Rust 侧会把它从 inline 配置里剥离，
 * 转成 rclone RC 顶层的 no_check_certificate；rclone 的 webdav 后端本身没有这个 option。
 */
object WebDavProtocol : RepoProtocol {

    override val key: String = "webdav"

    override val label: String = "WebDAV"

    override val auths: List<RepoAuthOption> = listOf(RepoAuthOption("password", "密码"))

    private const val USER = "user"
    private const val PASS = "password"
    private const val INSECURE = "insecure"

    override fun fields(repo: RemoteRepo): List<RepoField> = listOf(
        RepoField.Text(
            id = FIELD_HOST,
            label = "服务地址（URL）",
            placeholder = if (repo.flag(INSECURE)) {
                "http://192.168.1.10:8080/dav"
            } else {
                "https://example.com/dav"
            },
            required = true
        ),
        RepoField.Toggle(
            id = INSECURE,
            label = "允许不安全的服务",
            hint = "允许 http 明文连接，并跳过自签名 / 过期证书校验"
        ),
        RepoField.Text(USER, "用户名", "用户名", required = true),
        RepoField.Text(PASS, "服务密码", secret = true, required = true)
    )

    override fun rcloneOptions(repo: RemoteRepo): Map<String, String> {
        val host = repo[FIELD_HOST].trim().trimEnd('/')
        val insecure = repo.flag(INSECURE)
        return buildMap {
            put(
                "url",
                if (host.contains("://")) host else "${if (insecure) "http" else "https"}://$host"
            )
            put("user", repo[USER])
            put("pass", repo[PASS])
            // 只有打开时才下发；Rust 侧默认就是 false
            if (insecure) put(INSECURE, "true")
        }
    }

    override fun address(repo: RemoteRepo): String {
        val raw = repo[FIELD_HOST].trim().trimEnd('/')
        // 地址可能已经带上了 scheme，别重复补
        val base = if (raw.contains("://")) {
            raw
        } else {
            "${if (repo.flag(INSECURE)) "http" else "https"}://$raw"
        }
        return withPath(base, repo)
    }
}
