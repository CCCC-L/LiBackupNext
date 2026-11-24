package com.cc.libackupnext.service.gateway

import com.cc.libackupnext.data.prefs.RepoPrefs
import com.cc.libackupnext.data.FailureReason
import com.cc.libackupnext.data.RemoteRepo
import com.cc.libackupnext.data.SavedRepo
import com.cc.libackupnext.data.json.RepoLayoutRequest
import com.cc.libackupnext.data.json.RepoLayoutResponse
import com.cc.libackupnext.service.json.parseJson
import com.cc.libackupnext.service.json.toJsonText
import com.cc.libackupnext.service.rclone.RcloneClient
import com.cc.libackupnext.service.rclone.RemoteBackend
import com.cc.libackupnext.service.gateway.RusticSession.Companion.EMPTY_REQUEST
import com.cc.libackupnext.service.gateway.RusticSession.Companion.TAG
import com.cc.libackupnext.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 仓库生命周期：初始化、切换、编辑、凭据失效。
 *
 * 这些操作会改仓库配置本身，失败必须不落盘，所以只出现在设置 / 仓库列表 / 引导页。
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

    /**
     * 初始化 / 切换前测连。不加「连接测试失败：」前缀 —— RcloneClient 已归好类，
     * 再套一层只会让文案变长。
     */
    override suspend fun testConnection(repo: RemoteRepo): Result<Unit> =
        RcloneClient.testRemoteConnection(repo).map { }

    /**
     * 校验候选仓库：先查目录结构，再决定建库还是打开。
     *
     * 必须在 open 之前判：缺 keys 的 open 会报 C002（密码错），用户会以为自己记错了。
     * @param allowInit 只有新建才允许建库；切换 / 编辑传 false，别在错误的位置建新仓库。
     */
    private suspend fun verifyRepository(
        path: String,
        password: String,
        options: String,
        allowInit: Boolean
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val op = AppLog.startOperation("initRepo", path)
        val startedAt = System.currentTimeMillis()
        try {
            // 候选配置还没写进 RepoPrefs：只拿 binder，不注入当前仓库的凭据，
            // 免得旧仓库连不上时把刚起好的候选 serve 顶掉。
            val layout = readLayout(path, options).getOrElse { error ->
                AppLog.finishOperation(op, "initRepo", path, startedAt, "failed: ${error.message}")
                return@withContext Result.failure(error)
            }

            val result = when (layout.state) {
                // 什么都没有：新建时直接建库；切换 / 编辑时说明这里不是仓库
                RepoLayoutState.EMPTY -> if (allowInit) {
                    openOrInit(path, password, options, forceInit = true)
                } else {
                    Result.failure(notARepository())
                }
                // config + keys 都在：只打开，失败原样返回（不再偷偷 init）
                RepoLayoutState.COMPLETE -> openOrInit(path, password, options, forceInit = false)
                // 缺件：建库会留孤儿数据，打开也打不开，只能明说
                RepoLayoutState.INCOMPLETE -> Result.failure(incompleteRepository())
            }
            result.fold(
                onSuccess = {
                    AppLog.finishOperation(op, "initRepo", path, startedAt, "ok ${layout.state}")
                },
                onFailure = {
                    AppLog.finishOperation(op, "initRepo", path, startedAt, "failed: ${it.message}")
                }
            )
            result
        } catch (t: Throwable) {
            AppLog.e(TAG, "verifyRepository failed", t, op)
            AppLog.finishOperation(op, "initRepo", path, startedAt, "failed: ${t.message}")
            val raw = t.message?.takeIf { it.isNotBlank() } ?: t.toString()
            Result.failure(session.remote.backendFailure("initRepo 异常", RusticFailure(raw), t))
        }
    }

    /** 一次 IPC：建库或打开，由 [forceInit] 决定（结构已经查过了）。 */
    private suspend fun openOrInit(
        path: String,
        password: String,
        options: String,
        forceInit: Boolean
    ): Result<Unit> {
        val failure = parseRusticReply(
            session.binder().initRepo(path, password, options, forceInit)
        )
        return if (failure == null) {
            Result.success(Unit)
        } else {
            Result.failure(session.remote.backendFailure("initRepo", failure))
        }
    }

    /** 查仓库目录结构；连不上之类的失败原样返回。 */
    private suspend fun readLayout(path: String, options: String): Result<RepoLayout> = try {
        val request = RepoLayoutRequest(
            path = path,
            options = runCatching { options.parseJson<Map<String, String>>() }
                .getOrDefault(emptyMap())
        )
        val response = session.binder().repoLayout(request.toJsonText())
            .parseJson<RepoLayoutResponse>()
        if (response.ok) {
            Result.success(
                RepoLayout(
                    config = response.config,
                    keys = response.keys,
                    data = response.data,
                    index = response.index,
                    snapshots = response.snapshots
                )
            )
        } else {
            Result.failure(
                session.remote.backendFailure(
                    "repoLayout",
                    failureOf(response.error, response.reason, "读取仓库结构失败")
                )
            )
        }
    } catch (t: Throwable) {
        AppLog.e(TAG, "readLayout failed", t)
        Result.failure(t)
    }

    /** 目录缺件：不是网络问题，也不能靠重试解决，直接给一句明确文案。 */
    private fun incompleteRepository(): Throwable =
        BackendFailureException(FailureReason.REPO_INCOMPLETE.text)

    /** 切换 / 编辑时指向了一个空目录：这里不是仓库，绝不能顺手建一个新的。 */
    private fun notARepository(): Throwable =
        BackendFailureException("这个目录不是 rustic 仓库")

    /** 远程服务配置库：注入凭据时按 id 找回它重新派生 options。 */
    private fun syncRemoteRepos(repo: RemoteRepo) {
        repoPrefs.setRemoteRepos(repoPrefs.remoteRepos.filter { it.id != repo.id } + repo)
    }

    /** 远程配置每次重新向 rclone 服务取地址：URL 只在本次会话内有效，不落盘。 */
    private suspend fun remoteOptions(repo: RemoteRepo): Result<String> =
        RcloneClient.ensureServe(repo).map { RemoteBackend.optionsFor(it) }

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

        // 切换是「接上已有仓库」，只有新建才允许在空目录里建库
        return verifyRepository(path, password, options, allowInit = !switching).onSuccess {
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
        val options = if (remote != null) {
            remoteOptions(remote).getOrElse { return Result.failure(it) }
        } else EMPTY_REQUEST
        val savedOptions = if (remote == null) options else EMPTY_REQUEST
        val isCurrent = old.location == repoPrefs.location
        // 编辑已保存的仓库：不允许建库（改地址 / 改密码不该产生新仓库）
        return verifyRepository(location, password, options, allowInit = false).onSuccess {
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
