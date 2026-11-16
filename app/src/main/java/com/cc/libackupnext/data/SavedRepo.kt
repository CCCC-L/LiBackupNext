package com.cc.libackupnext.data

import com.cc.libackupnext.data.protocol.LocalProtocol

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
        get() = remote?.let { it.protocol.address(it) } ?: location

    /** 编辑弹窗的表单初值：远程直接还原，本地按目录构造一份 LOCAL 配置。 */
    fun toForm(): RemoteRepo = remote ?: RemoteRepo(
        id = id,
        name = name.ifBlank { LocalProtocol.defaultName },
        path = location
    )

    companion object {
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
                else -> LocalProtocol.defaultName
            },
            location = location,
            password = password,
            remote = remote
        )
    }
}
