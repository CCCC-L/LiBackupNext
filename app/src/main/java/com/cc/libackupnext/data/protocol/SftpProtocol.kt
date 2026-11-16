package com.cc.libackupnext.data.protocol

import com.cc.libackupnext.data.RemoteRepo

/**
 * SFTP：密码或公钥登录，地址是「主机:端口」，端口缺省 22。
 *
 * 公钥登录时不下发密码，私钥转成 rclone 要求的单行 key_pem。
 */
object SftpProtocol : RepoProtocol {

    override val key: String = "sftp"

    override val label: String = "SFTP"

    override val auths: List<RepoAuthOption> = listOf(
        RepoAuthOption("password", "密码"),
        RepoAuthOption("public_key", "公钥")
    )

    private const val USER = "user"
    private const val PASS = "password"
    private const val PRIVATE_KEY = "privateKey"
    private const val AUTH_PUBLIC_KEY = "public_key"

    override fun fields(repo: RemoteRepo): List<RepoField> = buildList {
        add(RepoField.Text(FIELD_HOST, "地址（地址:端口）", "192.168.1.10:22", required = true))
        add(RepoField.Select(FIELD_AUTH, "认证方式", auths))
        add(RepoField.Text(USER, "用户名", "用户名", required = true))
        if (effectiveAuth(repo)?.key == AUTH_PUBLIC_KEY) {
            add(
                RepoField.Text(
                    id = PRIVATE_KEY,
                    label = "私钥（PEM）",
                    placeholder = "粘贴未加密的 PEM 私钥\n-----BEGIN … PRIVATE KEY-----",
                    multiline = true,
                    required = true
                )
            )
        } else {
            add(RepoField.Text(PASS, "服务密码", secret = true, required = true))
        }
    }

    override fun rcloneOptions(repo: RemoteRepo): Map<String, String> {
        val (host, port) = splitHostPort(repo[FIELD_HOST], "22")
        val publicKey = effectiveAuth(repo)?.key == AUTH_PUBLIC_KEY
        return buildMap {
            put("host", host)
            put("port", port)
            put("user", repo[USER])
            if (publicKey) {
                put("key_pem", singleLinePem(repo[PRIVATE_KEY]))
            } else {
                put("pass", repo[PASS])
            }
        }
    }

    override fun address(repo: RemoteRepo): String =
        withPath("sftp://${stripScheme(repo[FIELD_HOST])}", repo)
}
