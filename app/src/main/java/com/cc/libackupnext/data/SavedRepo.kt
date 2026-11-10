package com.cc.libackupnext.data

import org.json.JSONObject

/**
 * 已添加的仓库记录：本地和远程共用一份清单。
 *
 * 只存配置，不缓存仓库里的备份数据。[location] 与 AppPrefs.repoPath 的取值一致
 * （本地是目录，远程是 `remote:<id>:<path>`），清单按它去重、判定「当前使用中」。
 * [password] 是 rustic 仓库密码：切换 / 编辑时要靠它重新校验；业务请求仍只从
 * AppPrefs.repoPassword 读，清单不是凭据真源。
 */
data class SavedRepo(
    val id: String,
    val name: String,
    val location: String,
    val password: String,
    /** 远程配置（含服务凭据）；本地仓库为 null。编辑弹窗由此还原表单。 */
    val remote: RemoteRepo? = null
) {
    val isRemote: Boolean get() = remote != null

    /**
     * 给用户看的真实地址：本地是目录，远程是 `协议://地址/仓库路径`，
     * 不暴露 `remote:<id>:<path>` 这种内部逻辑标识。绝不带密码或私钥。
     */
    val address: String
        get() {
            val r = remote ?: return location
            val rawHost = r.host.trim().trimEnd('/')
            val base = when (r.protocol) {
                RepoProtocol.LOCAL -> return location
                // WebDAV 的地址可能已经带上了 scheme，别重复补
                RepoProtocol.WEBDAV ->
                    if (rawHost.contains("://")) rawHost
                    else "${if (r.insecure) "http" else "https"}://$rawHost"
                // 用户可能填了 sftp://host:22，先去掉再补当前协议的 scheme
                RepoProtocol.SFTP -> "sftp://${rawHost.substringAfter("://")}"
                RepoProtocol.SMB -> "smb://${rawHost.substringAfter("://")}"
            }
            val path = r.path.trim().trimStart('/')
            return if (path.isBlank()) base else "${base.trimEnd('/')}/$path"
        }

    /** 编辑弹窗的表单初值：远程直接还原，本地按目录构造一份 LOCAL 配置。 */
    fun toForm(): RemoteRepo = remote ?: RemoteRepo(
        id = id,
        name = name.ifBlank { RepoProtocol.LOCAL.defaultName },
        protocol = RepoProtocol.LOCAL,
        path = location
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("location", location)
        put("password", password)
        if (remote != null) put("remote", remote.toJson())
    }

    companion object {
        fun fromJson(o: JSONObject): SavedRepo = SavedRepo(
            id = o.optString("id"),
            name = o.optString("name"),
            location = o.optString("location"),
            password = o.optString("password"),
            remote = o.optJSONObject("remote")?.let { RemoteRepo.fromJson(it) }
        )

        /** 初始化 / 编辑成功后由业务层构造一条记录；本地名称可由表单带过来。 */
        fun of(
            location: String,
            password: String,
            remote: RemoteRepo?,
            name: String = ""
        ): SavedRepo = SavedRepo(
            id = remote?.id ?: location,
            name = when {
                name.isNotBlank() -> name
                remote != null -> remote.name.ifBlank { remote.protocol.label }
                else -> RepoProtocol.LOCAL.defaultName
            },
            location = location,
            password = password,
            remote = remote
        )
    }
}
