package com.cc.libackupnext.service.gateway

import com.cc.libackupnext.data.prefs.RepoPrefs
import com.cc.libackupnext.data.RemoteRepo
import com.cc.libackupnext.data.SavedRepo
import com.cc.libackupnext.service.rclone.RcloneClient
import com.cc.libackupnext.service.rclone.RemoteBackend
import com.cc.libackupnext.service.gateway.RusticSession.Companion.EMPTY_REQUEST
import com.cc.libackupnext.service.gateway.RusticSession.Companion.TAG
import com.cc.libackupnext.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 仓库生命周期的实现：初始化、切换、编辑、凭据失效。
 *
 * 和读写的区别：这些操作**会改仓库配置本身**，失败时必须能区分
 * 「连接测试失败」和「开库失败」，并且保证失败不落盘 ——
 * 所以它们只出现在设置 / 仓库列表 / 引导页，不出现在备份恢复的调用面上。
 */
internal class RusticAdminGateway(
    private val session: RusticSession,
    private val repoPrefs: RepoPrefs,
) : RepoAdmin {

    override fun invalidateCredentials() {
        session.invalidateCredentials()
    }

    override fun removeSavedRepo(location: String) {
        repoPrefs.removeSavedRepo(location)
    }

    /** 初始化 / 切换远程仓库前测连：失败返回带原因的失败，由 UI 层提示。 */
    override suspend fun testConnection(repo: RemoteRepo): Result<Unit> =
        RcloneClient.testRemoteConnection(repo).fold(
            onSuccess = { Result.success(Unit) },
            onFailure = {
                val message = "连接测试失败：${it.message ?: "未知错误"}"
                Result.failure(IllegalStateException(message, it))
            }
        )

    /** 校验候选仓库：已有仓库密码正确时 open 成功，空仓库才尝试建库。 */
    private suspend fun verifyRepository(
        path: String,
        password: String,
        options: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val op = AppLog.startOperation("initRepo", path)
        val startedAt = System.currentTimeMillis()
        try {
            // 候选配置还没写进 RepoPrefs：只拿 binder，不注入当前仓库的凭据，
            // 免得旧仓库连不上时把刚起好的候选 serve 顶掉。initRepo 只读候选值，
            // 这里不归一，好把「连接测试失败」和「开库失败」分开；原文仍完整写日志。
            val error = session.binder().initRepo(path, password, options)
            if (error.isNullOrBlank()) {
                AppLog.finishOperation(op, "initRepo", path, startedAt, "ok")
                Result.success(Unit)
            } else {
                AppLog.finishOperation(op, "initRepo", path, startedAt, "failed: $error")
                Result.failure(session.remote.backendFailure("initRepo", error))
            }
        } catch (t: Throwable) {
            AppLog.e(TAG, "verifyRepository failed", t, op)
            AppLog.finishOperation(op, "initRepo", path, startedAt, "failed: ${t.message}")
            val raw = t.message?.takeIf { it.isNotBlank() } ?: t.toString()
            Result.failure(session.remote.backendFailure("initRepo 异常", raw, t))
        }
    }

    /** 远程服务配置库：注入凭据时按 id 找回它重新派生 options。 */
    private fun syncRemoteRepos(repo: RemoteRepo) {
        repoPrefs.setRemoteRepos(repoPrefs.remoteRepos.filter { it.id != repo.id } + repo)
    }

    /** 远程配置每次重新向 rclone 服务取地址：URL 只在本次会话内有效，不落盘。 */
    private suspend fun remoteOptions(repo: RemoteRepo): Result<String> =
        RcloneClient.ensureServe(repo).map { RemoteBackend.optionsFor(it) }

    /**
     * 本地仓库先确认 config 存在：initRepo 的语义是「打开失败就在空目录建库」，
     * 切换 / 编辑时不该因为路径写错而悄悄新建一个空仓库。
     */
    private fun localRepoError(location: String, isRemote: Boolean): String? =
        if (!isRemote && !File(location, "config").isFile) {
            "仓库目录里没有 rustic 配置：$location"
        } else {
            null
        }

    override suspend fun initRepository(
        path: String,
        password: String,
        options: String,
        remote: RemoteRepo?,
        switching: Boolean
    ): Result<Unit> {
        // 空位置 / 空密码都不该建仓库：空密码等于把备份完全敞开
        if (path.isBlank()) {
            return Result.failure(IllegalStateException("请先选择仓库目录"))
        }
        if (password.isBlank()) {
            return Result.failure(IllegalStateException("请先输入仓库密码"))
        }
        if (switching) {
            localRepoError(path, remote != null)?.let {
                return Result.failure(IllegalStateException(it))
            }
        }

        return verifyRepository(path, password, options).onSuccess {
            // 远程的 options 是本次会话的 rest_url（含随机 token），不能写进 RepoPrefs；
            // 下次绑定会重新向 rclone 服务取地址。
            repoPrefs.save(
                path, password, if (remote == null) options else EMPTY_REQUEST
            )
            if (!switching) {
                repoPrefs.addSavedRepo(
                    SavedRepo.of(path, password, remote, remote?.name.orEmpty())
                )
            }
            if (remote != null) syncRemoteRepos(remote)
            // 成功才让下一次 service() 把新凭据注入到 root / Rust 侧
            invalidateCredentials()
        }
    }

    override suspend fun initRemoteRepository(
        repo: RemoteRepo,
        password: String,
        switching: Boolean
    ): Result<Unit> {
        if (!repo.protocol.isLocal) {
            testConnection(repo).getOrElse { return Result.failure(it) }
        }
        val options = remoteOptions(repo).getOrElse { return Result.failure(it) }
        return initRepository(repo.location, password, options, repo, switching)
    }

    override suspend fun updateSavedRepo(
        old: SavedRepo,
        form: RemoteRepo,
        password: String
    ): Result<Unit> {
        val location = form.location
        if (location.isBlank()) {
            return Result.failure(IllegalStateException("请先选择仓库目录"))
        }
        if (password.isBlank()) {
            return Result.failure(IllegalStateException("请先输入仓库密码"))
        }
        val remote = form.takeIf { !it.protocol.isLocal }
        localRepoError(location, remote != null)?.let {
            return Result.failure(IllegalStateException(it))
        }
        val options = if (remote != null) {
            remoteOptions(remote).getOrElse { return Result.failure(it) }
        } else EMPTY_REQUEST
        val savedOptions = if (remote == null) options else EMPTY_REQUEST
        val isCurrent = old.location == repoPrefs.location
        return verifyRepository(location, password, options).onSuccess {
            repoPrefs.replaceSavedRepo(
                old.location,
                SavedRepo.of(location, password, remote, form.name)
            )
            if (remote != null) syncRemoteRepos(remote)
            if (isCurrent) {
                repoPrefs.save(location, password, savedOptions)
                invalidateCredentials()
            }
        }
    }
}
