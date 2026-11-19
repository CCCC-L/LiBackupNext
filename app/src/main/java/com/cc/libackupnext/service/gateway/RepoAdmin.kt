package com.cc.libackupnext.service.gateway

import com.cc.libackupnext.data.RemoteRepo
import com.cc.libackupnext.data.SavedRepo

/**
 * 仓库生命周期：初始化、切换、编辑、凭据失效。
 *
 * 与 [RepoWriter] 分开的原因：这些操作会**改变仓库配置本身**，
 * 失败时调用方必须能区分「连接测试失败」和「开库失败」并保证不落盘；
 * 它们只出现在设置 / 仓库列表页，不该出现在备份恢复的调用面上。
 */
interface RepoAdmin {

    /**
     * 初始化 / 校验 / 切换 rustic 仓库，成功后才记住地址、密码和后端选项。
     *
     * 候选值只随这一次请求下传，不写全局配置；失败时配置保持原样，无需回滚。
     *
     * @param path 本地文件系统路径，或 [RemoteRepo.location]
     * @param options rustic_backend 的后端选项 JSON；本地传 "{}"
     * @param remote 远程服务配置；非空时同步进已保存的远程仓库
     * @param switching 切换已有仓库：本地目录必须已有 config，避免误建空库
     */
    suspend fun initRepository(
        path: String,
        password: String,
        options: String = "{}",
        remote: RemoteRepo? = null,
        switching: Boolean = false
    ): Result<Unit>

    /**
     * 初始化 / 切换远程仓库：位置和后端选项由实现从 [RemoteRepo] 派生。
     *
     * 非本地协议先测连接，失败直接返回、不落盘。
     */
    suspend fun initRemoteRepository(
        repo: RemoteRepo,
        password: String,
        switching: Boolean = false
    ): Result<Unit>

    /** 编辑已添加的仓库：校验通过才更新清单；改的是当前仓库时同步当前凭据。 */
    suspend fun updateSavedRepo(
        old: SavedRepo,
        form: RemoteRepo,
        password: String
    ): Result<Unit>

    /** 凭据需要重新对齐（初始化成功 / 服务重启）：下次调用重新注入真源。 */
    fun invalidateCredentials()

    /** 从「已添加仓库」清单里移除一条（含密码），不动仓库里的备份数据。 */
    fun removeSavedRepo(location: String)

    /**
     * 只测连接、不建库、不落盘：给仓库配置表单的「测试连接」用。
     *
     * 失败时返回的 message 已经是可展示的文案（含「连接测试失败：」前缀），
     * 调用方直接显示，不用再拼一遍上下文。
     */
    suspend fun testConnection(repo: RemoteRepo): Result<Unit>
}
