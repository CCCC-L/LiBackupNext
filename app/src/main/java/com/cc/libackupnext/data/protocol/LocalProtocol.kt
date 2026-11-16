package com.cc.libackupnext.data.protocol

import com.cc.libackupnext.data.RemoteRepo

/**
 * 本地仓库：只有目录，没有服务端配置。
 *
 * 目录由通用文件夹选择弹窗回填到 [RemoteRepo.path]，所以这里不声明表单字段；
 * 目录浏览走 rclone 的 local 后端，配置由 RemoteBackend.localFs() 生成。
 */
object LocalProtocol : RepoProtocol {

    override val key: String = "local"

    override val label: String = "本地"

    override val isLocal: Boolean = true

    override fun fields(repo: RemoteRepo): List<RepoField> = emptyList()

    override fun rcloneOptions(repo: RemoteRepo): Map<String, String> = emptyMap()

    /** 本地直接显示目录。 */
    override fun address(repo: RemoteRepo): String = repo.path

    /** 本地只要目录，名称不参与校验。 */
    override fun isComplete(repo: RemoteRepo): Boolean = repo.path.isNotBlank()
}
