package com.cc.libackupnext.service

import android.os.IBinder
import android.util.Log
import com.cc.libackupnext.App
import com.cc.libackupnext.data.AppBackupSummary
import com.cc.libackupnext.data.AppPrefs
import com.cc.libackupnext.data.BackupEntry
import com.cc.libackupnext.data.BackupGroup
import com.cc.libackupnext.data.BackupRequest
import com.cc.libackupnext.data.BackupSnapshot
import com.cc.libackupnext.data.BackupStep
import com.cc.libackupnext.data.BackupStepStatus
import com.cc.libackupnext.data.BackupTarget
import com.cc.libackupnext.data.RemoteRepo
import com.cc.libackupnext.data.RepoOverview
import com.cc.libackupnext.data.SavedRepo
import com.cc.libackupnext.data.SnapshotKind
import com.cc.libackupnext.data.SnapshotMeta
import com.cc.libackupnext.data.SnapshotOverride
import com.cc.libackupnext.data.kind
import com.cc.libackupnext.data.json.BackupOptions
import com.cc.libackupnext.data.json.CleanupResponse
import com.cc.libackupnext.data.json.DeleteSnapshotsRequest
import com.cc.libackupnext.data.json.EstimateResponse
import com.cc.libackupnext.data.json.ExportSnapshotRequest
import com.cc.libackupnext.data.json.ListSnapshotsRequest
import com.cc.libackupnext.data.json.ListSnapshotsResponse
import com.cc.libackupnext.data.json.OverviewResponse
import com.cc.libackupnext.data.json.RestoreKindItem
import com.cc.libackupnext.data.json.RestoreSnapshotsRequest
import com.cc.libackupnext.data.json.SnapshotOverrideDto
import com.cc.libackupnext.data.json.SystemDataCountsResponse
import com.cc.libackupnext.data.json.UpdateDescriptionRequest
import com.cc.libackupnext.entity.AppInfo
import com.cc.libackupnext.service.json.parseJson
import com.cc.libackupnext.service.json.toJsonText
import com.cc.libackupnext.service.util.BackendMessage
import com.cc.libackupnext.service.util.BackendRawException
import com.cc.libackupnext.service.util.BackendSource
import com.cc.libackupnext.service.util.SystemDataExporter
import com.cc.libackupnext.util.AppLog
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 业务层：主进程直接用的入口。
 *
 * 和 [RusticServer]（root 侧）分开放 —— 那边是 `RootService` + JNI，
 * 这边是绑定服务、拼请求、把结果整理成 UI 能直接渲染的模型。
 */
object RusticClient {

    private const val TAG = "Rustic"

    /** 远程断连时 UI 的唯一提示文案，也作为 [isRemoteConnectionFailure] 的判定标记。 */
    const val REMOTE_INTERRUPTED_MESSAGE = "远程仓库暂时无法连接，请确认是否可以连接"

    private val manager by lazy {
        RootServiceManager<IRusticService>(RusticServer.Service::class.java) {
            IRusticService.Stub.asInterface(it)
        }
    }

    /** 不含凭据的空请求：仓库地址和密码只由 setRepo() 注入。 */
    private const val EMPTY_REQUEST = "{}"

    /** 初始化 / 切换远程仓库前测连：失败返回带原因的失败，由 UI 层提示。 */
    private suspend fun testConnection(repo: RemoteRepo): Result<Unit> =
        RcloneClient.testRemoteConnection(repo).fold(
            onSuccess = { Result.success(Unit) },
            onFailure = {
                val message = "连接测试失败：${it.message ?: "未知错误"}"
                Result.failure(IllegalStateException(message, it))
            }
        )

    private val credentialLock = Mutex()

    @Volatile
    private var credentialVersion = 0L
    private var injectedBinder: IBinder? = null
    private var injectedVersion = -1L

    /** 凭据需要重新对齐（初始化成功 / 服务重启）：下次调用重新注入真源。 */
    fun invalidateCredentials() {
        credentialVersion++
    }

    /**
     * rustic 失败的统一收敛：完整原文进日志，UI 只显示底层 message。
     */
    private fun backendFailure(
        label: String,
        raw: String,
        cause: Throwable? = null
    ): IllegalStateException {
        val brief = BackendMessage.record(
            source = BackendSource.RUSTIC,
            raw = raw,
            error = raw,
            ok = false,
            label = label
        )
        return IllegalStateException(
            brief.text.ifBlank { "操作失败" },
            cause ?: BackendRawException(BackendSource.RUSTIC, raw)
        )
    }

    /**
     * 业务请求不再预先测连：注入凭据时 [RcloneClient.ensureServe] 已经建立起 rclone
     * 后端连接，远端不可达会由第一笔真实请求失败暴露，再经 [remoteCall] 统一处理。
     */
    private suspend fun remoteFailure(t: Throwable): Throwable {
        if (t is CancellationException || isRemoteConnectionFailure(t)) return t

        // 部分异常没有 message（NPE / 解析异常）：至少把异常类型记进原文
        val raw = t.message?.takeIf { it.isNotBlank() } ?: t.toString()
        val concise = backendFailure("remoteFailure", raw, t)
        if (!AppPrefs.isRemote) return concise

        // 原始异常的整条 Caused by 链仍写一份；正文带原文，完整原文另见 RusticResponse
        AppLog.e(TAG, "远程仓库连接中断：${concise.message}", t)
        invalidateCredentials()
        // stopServe() 是 suspend：用 try/catch 吞掉它自己的异常，不能用 runCatching 包
        try {
            RcloneClient.stopServe()
        } catch (stopError: Throwable) {
            AppLog.w(TAG, "停止远程服务失败", stopError)
        }
        return IllegalStateException(REMOTE_INTERRUPTED_MESSAGE, concise)
    }

    /** UI 只调这个判断是否断。 */
    fun isRemoteConnectionFailure(t: Throwable?): Boolean =
        t is IllegalStateException && t.message == REMOTE_INTERRUPTED_MESSAGE

    /**
     * 所有访问仓库的 rustic 调用都过这里：远程失败统一换成 [REMOTE_INTERRUPTED_MESSAGE]，
     * 让 UI 认得出断连、批量能停批。各方法现有的 catch 不用改，拿到的已是归一化异常。
     *
     * block 必须是 suspend（里面要调 [getService]）；catch 里要调 suspend 的
     * [remoteFailure]（停 serve），所以用 try/catch 而不是 runCatching。
     */
    private suspend fun <T> remoteCall(block: suspend () -> T): T = try {
        block()
    } catch (t: Throwable) {
        throw remoteFailure(t)
    }

    /**
     * root 侧把 rustic 的失败包成 error 字符串 / `ok=false` 返回，并不抛异常。
     * 这些「软错误」同样要归一化，否则断连时 UI 认不出、批量不会停批。
     */
    private suspend fun remoteError(message: String): Throwable =
        remoteFailure(IllegalStateException(message.ifBlank { "仓库操作失败" }))

    /** 只绑定 root 服务：候选仓库校验、不碰仓库的本机操作都不需要注入当前凭据。 */
    private suspend fun bindService(): IRusticService = manager.getService()

    /**
     * 绑定并按需注入凭据；所有访问仓库的 rustic 命令的唯一出口。
     *
     * 不再在每笔请求前测连：只有 [initRemoteRepository] 会显式测一次，好把
     * 「连接测试失败」和「开库失败」分开、并保证失败不落盘。仓库访问一律走
     * [remoteCall]；[verifyRepository] / [systemDataCounts] / [setWifiEnabled]
     * 用 [bindService] 只拿 binder，不注入当前仓库的凭据。
     */
    private suspend fun getService(): IRusticService {
        val remote = if (AppPrefs.isRemote) {
            AppPrefs.remoteRepos.first { repoPath.startsWith("remote:${it.id}:") }
        } else null

        val service = bindService()
        credentialLock.withLock {
            val binder = service.asBinder()
            if (injectedBinder !== binder || injectedVersion != credentialVersion) {
                // 只记「注入了凭据」，绝不能把密码写进日志
                AppLog.i(TAG, "绑定 root 服务，注入仓库凭据")
                // 远程仓库的地址由 rclone 服务临时起 serve 取回，只用于本次注入；
                // 配置变化后 root 侧按 key 换服务，URL 含随机 token，绝不落盘。
                val options = if (remote != null) {
                    RemoteBackend.optionsFor(RcloneClient.ensureServe(remote).getOrThrow())
                } else repoOptions
                service.setRepo(repoPath, repoPassword, options)
                injectedBinder = binder
                injectedVersion = credentialVersion
            }
        }
        return service
    }

    /**
     * 断开并重启 root 服务进程。
     *
     * 应用升级后旧进程还跑着上一版 dex，新加的 AIDL 方法会调用失败，
     * 启动时先断一次，确保进程里跑的是当前 APK 的代码。
     */
    fun restart() {
        AppLog.i(TAG, "主动断开 root 服务，等待重连")
        manager.release()
        invalidateCredentials()
    }

    // ==================== 快照缓存 ====================

    /**
     * 每个包的备份概况（次数 + 最近一次备份 + 最新 apk 快照）。
     * 不再拉「全量快照列表」：那样响应大小跟备份次数成正比，仓库大了会撑爆共享内存。
     * 概况只跟应用数量有关，200 个应用约 160KB。
     * 失败以 `Result.failure` 返回（远程会带归一后的断连异常），由 UI 决定提示方式。
     */
    suspend fun repoOverview(): Result<List<RepoOverview>> = withContext(Dispatchers.IO) {
        if (!AppPrefs.hasRepository()) return@withContext Result.success(emptyList())

        try {
            val response = remoteCall { getService().overview(EMPTY_REQUEST) }
                .parseJson<OverviewResponse>()
            if (!response.ok) {
                val message = response.error.orEmpty().ifBlank { "读取备份概况失败" }
                AppLog.e("备份概况", message)
                // 远程软错误归一一次（停掉旧 serve，让下次请求重建连接），
                // 作为 Result.failure 交给 UI 决定怎么提示；本地读失败仍退化为空列表
                if (AppPrefs.isRemote) {
                    return@withContext Result.failure(
                        remoteFailure(IllegalStateException(message))
                    )
                }
                return@withContext Result.success(emptyList())
            }

            Result.success(response.packages.mapNotNull { item ->
                val packageName = item.packageName
                if (packageName.isBlank()) return@mapNotNull null
                // 同一次备份里 tag / 时间 / 备注都一样，Rust 侧只回传一份
                val description = item.description?.takeIf { it.isNotBlank() }
                RepoOverview(
                    packageName = packageName,
                    count = item.count,
                    time = item.time,
                    tags = item.tags,
                    description = description,
                    snapshots = item.snapshots.map { snapshot ->
                        BackupSnapshot(
                            id = snapshot.id,
                            label = snapshot.label,
                            tags = item.tags,
                            time = item.time,
                            size = snapshot.size,
                            description = description
                        )
                    },
                    hasApk = item.hasApk,
                    apkTags = item.apkTags?.takeIf { it.isNotBlank() },
                    apkTime = item.apkTime,
                    hasIcon = item.hasIcon
                )
            })
        } catch (t: Throwable) {
            AppLog.e(TAG, "repoOverview failed", t)
            Result.failure(t)
        }
    }

    /**
     * 查询仓库里的快照。
     *
     * @param packageName 传 null 表示要全部快照
     */
    private suspend fun listSnapshots(
        packageName: String? = null
    ): Result<List<BackupSnapshot>> = withContext(Dispatchers.IO) {
        try {
            val request = ListSnapshotsRequest(packageName = packageName.orEmpty())
            val response = remoteCall { getService().listSnapshots(request.toJsonText()) }
                .parseJson<ListSnapshotsResponse>()
            if (!response.ok) {
                return@withContext Result.failure(
                    remoteError(response.error.orEmpty().ifBlank { "查询快照失败" })
                )
            }

            val snapshots = response.snapshots.map { item ->
                BackupSnapshot(
                    id = item.id,
                    label = item.label,
                    tags = item.tags,
                    time = item.time,
                    size = item.size,
                    description = item.description?.takeIf { it.isNotBlank() }
                )
            }
            Result.success(snapshots)
        } catch (t: Throwable) {
            AppLog.e(TAG, "listSnapshots failed", t)
            Result.failure(t)
        }
    }

    /** 删除一批快照。 */
    private suspend fun deleteSnapshots(
        ids: List<String>
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val request = DeleteSnapshotsRequest(ids = ids)
            val error = remoteCall { getService().deleteSnapshots(request.toJsonText()) }
            if (error.isNullOrBlank()) Result.success(Unit)
            else Result.failure(remoteError(error))
        } catch (t: Throwable) {
            AppLog.e(TAG, "deleteSnapshots failed", t)
            Result.failure(t)
        }
    }

    /**
     * 备份一个应用。
     *
     * @param onProgress 在 binder 线程回调，注意线程安全
     */
    private suspend fun runBackup(
        request: BackupRequest,
        overrides: Map<String, SnapshotOverride> = emptyMap(),
        deleteIds: List<String> = emptyList(),
        batchId: String? = null,
        /**
         * 一次「逻辑操作」的标识，写进所有快照的 tag
         */
        operationId: Long = System.currentTimeMillis(),
        iconAlreadyBackedUp: Boolean? = null,
        parentIds: Map<SnapshotKind, String>? = null,
        onProgress: (BackupProgress) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val op = AppLog.startOperation("backup", request.packageName)
        val startedAt = System.currentTimeMillis()
        try {
            val callback = object : IBackupProgressCallback.Stub() {
                override fun onProgress(progress: String) {
                    runCatching { onProgress(progress.parseJson<BackupProgress>()) }
                }
            }

            // 目录 / 系统数据都没有图标，跳过这一步（系统数据也就不用去仓库里查了）
            val isDir = request.packageName.startsWith('/')
            val isFixed = request.packageName in BackupTarget.fixedIds

            // 图标只保留一份：仓库里已有 pkg:icon 就不再备份。
            // 批量备份时调用方已经知道答案（来自备份概况），不用再查一遍。
            val hasIcon = isDir || isFixed || iconAlreadyBackedUp ?: listSnapshots(
                packageName = request.packageName
            ).getOrThrow().any { it.label == "${request.packageName}:${SnapshotKind.ICON.key}" }

            // op 透传到 :root，两侧日志用同一个 op 串起来
            val payload = BackupOptions(
                op = op,
                packageName = request.packageName,
                appName = request.appName,
                version = request.version,
                userId = request.userId,
                // 这些 key 就是 RusticRequestBuilder 里读取的那几项
                apk = request.backupApk,
                obb = request.backupObb,
                user = request.backupUser,
                data = request.backupData,
                permissions = request.backupPermissions,
                timestamp = operationId,
                includeIcon = !hasIcon && !isDir,
                parents = parentIds?.takeIf { it.isNotEmpty() }?.mapKeys { it.key.key },
                // 同批次的多个应用共用一次「打开仓库 + 读索引」
                batch = batchId?.takeIf { it.isNotBlank() },
                overrides = overrides.takeIf { it.isNotEmpty() }?.mapValues { (_, override) ->
                    SnapshotOverrideDto(
                        label = override.label,
                        tags = override.tags,
                        description = override.description
                    )
                }
            )

            val error = remoteCall { getService().backup(payload.toJsonText(), callback) }
            if (!error.isNullOrBlank()) {
                AppLog.finishOperation(op, "backup", request.packageName, startedAt, "failed: $error")
                return@withContext Result.failure(remoteError(error))
            }

            // 覆盖备份：新的写成功了再删旧的
            if (deleteIds.isNotEmpty()) {
                val deleteRequest = DeleteSnapshotsRequest(ids = deleteIds)
                val deleteError = remoteCall {
                    getService().deleteSnapshots(deleteRequest.toJsonText())
                }
                if (!deleteError.isNullOrBlank()) {
                    AppLog.finishOperation(
                        op, "backup", request.packageName, startedAt,
                        "failed: 删除旧快照 $deleteError"
                    )
                    return@withContext Result.failure(remoteError(deleteError))
                }
            }

            AppLog.finishOperation(op, "backup", request.packageName, startedAt, "ok")
            Result.success(Unit)
        } catch (t: Throwable) {
            AppLog.e(TAG, "backup failed", t, op)
            AppLog.finishOperation(op, "backup", request.packageName, startedAt, "failed: ${t.message}")
            Result.failure(remoteFailure(t))
        }
    }

    // ==================== 业务层 ====================
    // 下面是对外真正使用的方法：读 AppPrefs 里的仓库信息，做业务判断和数据处理。
    // UI 只拿这些方法的返回值渲染，不再自己拼请求、分组快照或判断勾选。

    private val repoPath: String get() = AppPrefs.repoPath
    private val repoPassword: String get() = AppPrefs.repoPassword
    private val repoOptions: String get() = AppPrefs.repoOptions

    /** 校验候选仓库：已有仓库密码正确时 open 成功，空仓库才尝试建库。 */
    private suspend fun verifyRepository(
        path: String,
        password: String,
        options: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val op = AppLog.startOperation("initRepo", path)
        val startedAt = System.currentTimeMillis()
        try {
            // 候选配置还没写进 AppPrefs：只拿 binder，不注入当前仓库的凭据，
            // 免得旧仓库连不上时把刚起好的候选 serve 顶掉。initRepo 只读候选值，
            // 这里不归一，好把「连接测试失败」和「开库失败」分开；原文仍完整写日志。
            val error = bindService().initRepo(path, password, options)
            if (error.isNullOrBlank()) {
                AppLog.finishOperation(op, "initRepo", path, startedAt, "ok")
                Result.success(Unit)
            } else {
                AppLog.finishOperation(op, "initRepo", path, startedAt, "failed: $error")
                Result.failure(backendFailure("initRepo", error))
            }
        } catch (t: Throwable) {
            AppLog.e(TAG, "verifyRepository failed", t, op)
            AppLog.finishOperation(op, "initRepo", path, startedAt, "failed: ${t.message}")
            val raw = t.message?.takeIf { it.isNotBlank() } ?: t.toString()
            Result.failure(backendFailure("initRepo 异常", raw, t))
        }
    }

    /** 远程服务配置库：getService() 注入凭据时按 id 找回它重新派生 options。 */
    private fun syncRemoteRepos(repo: RemoteRepo) {
        AppPrefs.remoteRepos = AppPrefs.remoteRepos.filter { it.id != repo.id } + repo
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

    /**
     * 初始化 / 校验 / 切换 rustic 仓库，成功后才记住地址、密码和后端选项。
     *
     * 候选值只随 initRepo() 这一次请求下传，不写全局配置。
     * 因此失败时 AppPrefs 保持原样，root / Rust 侧也没被改过，不需要任何回滚。
     *
     * @param path 本地文件系统路径，或 RemoteRepo.location
     * @param options rustic_backend 的后端选项 JSON；本地传默认的 "{}"
     * @param remote 远程服务配置；非空时同步进 remoteRepos，本地传 null
     * @param switching true 表示切换已有仓库：本地目录还必须有 config，避免误建空库
     */
    suspend fun initRepository(
        path: String,
        password: String,
        options: String = EMPTY_REQUEST,
        remote: RemoteRepo? = null,
        switching: Boolean = false
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
            // 远程的 options 是本次会话的 rest_url（含随机 token），不能写进 AppPrefs；
            // 下次绑定会重新向 rclone 服务取地址。
            AppPrefs.saveRepository(
                path, password, if (remote == null) options else EMPTY_REQUEST
            )
            if (!switching) {
                AppPrefs.addSavedRepo(
                    SavedRepo.of(path, password, remote, remote?.name.orEmpty())
                )
            }
            if (remote != null) syncRemoteRepos(remote)
            // 成功才让下一次 getService() 把新凭据注入到 root / Rust 侧
            invalidateCredentials()
        }
    }

    /**
     * 初始化 / 切换远程仓库：位置和后端选项都由业务层从 [RemoteRepo] 派生，UI 只传数据。
     *
     * 非本地协议先测连接，失败直接返回、不落盘；测连过了才起 serve、才尝试 initRepo。
     * 和 [initRepository] 一样是候选值语义，成功后才保存。
     * 切换清单里已有的仓库时传 `switching = true`，语义见 [initRepository]。
     */
    suspend fun initRemoteRepository(
        repo: RemoteRepo,
        password: String,
        switching: Boolean = false
    ): Result<Unit> {
        if (!repo.protocol.isLocal) {
            testConnection(repo).getOrElse { return Result.failure(it) }
        }
        val options = remoteOptions(repo).getOrElse { return Result.failure(it) }
        return initRepository(repo.location, password, options, repo, switching)
    }

    /**
     * 编辑已添加的仓库：校验通过才更新清单；改的是当前仓库时同步当前凭据。
     * 非当前仓库只改记录，不影响正在使用的仓库。
     */
    suspend fun updateSavedRepo(
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
        val isCurrent = old.location == AppPrefs.repoPath
        return verifyRepository(location, password, options).onSuccess {
            AppPrefs.replaceSavedRepo(
                old.location,
                SavedRepo.of(location, password, remote, form.name)
            )
            if (remote != null) syncRemoteRepos(remote)
            if (isCurrent) {
                AppPrefs.saveRepository(location, password, savedOptions)
                invalidateCredentials()
            }
        }
    }

    /**
     * 当前包的备份行（按备份时间分组，最新的在前）。
     *
     * 图标快照不展示，同一次备份的 apk / obb / user / user_de / data 合成一行。
     */
    suspend fun loadEntries(packageName: String, appName: String): Result<List<BackupEntry>> =
        runCatching {
            // 按包名查（rustic 侧按 label 前缀过滤），不再拉整个仓库的快照
            listSnapshots(packageName)
                .getOrThrow()
                .toEntries(appName)
        }

    /**
     * 仓库里有备份的所有应用（含已安装的），[AppInfo.onlyInBackup] 统一为 true。
     *
     * 只列「有 apk 备份」的包：没有 apk 的包恢复不了，不该出现在列表里。
     * 名字和版本取自**最新那条 apk 快照**的 tag —— 最近一次备份可能没勾 apk，
     * 那时 apk 快照是更早那次、版本也更旧，跟实际能恢复的包对得上。
     */
    suspend fun backupApps(userId: Int, overview: List<RepoOverview>? = null): List<AppInfo> {
        if (!AppPrefs.hasRepository()) return emptyList()

        // 调用方已经查过概况就直接用，避免列表页一次刷新查两遍仓库
        return (overview ?: repoOverview().getOrElse { emptyList() })
            .asSequence()
            .filter { it.hasApk }
            .map { overview ->
                val tags = overview.apkTags.orEmpty()
                AppInfo(
                    name = SnapshotMeta.appName(tags)?.ifBlank { overview.packageName }
                        ?: overview.packageName,
                    packageName = overview.packageName,
                    version = SnapshotMeta.version(tags).orEmpty(),
                    versionCode = 0,
                    installTime = 0,
                    updateTime = 0,
                    userId = userId,
                    isSystem = false,
                    isFrozen = false,
                    onlyInBackup = true
                )
            }
            .toList()
    }

    /**
     * 仓库里每个有备份的包：备份次数 + 最近一次备份。
     *
     * 一次 IPC 拿到所有包的概况（Rust 侧已经分好组），不再拉全量快照列表。
     *
     * @param appNames 包名 -> 应用名，用于拼每行的标题
     */
    suspend fun backupOverview(
        appNames: Map<String, String>,
        overview: List<RepoOverview>? = null
    ): List<AppBackupSummary> = buildSummaries(
        appNames,
        overview ?: repoOverview().getOrElse { emptyList() }
    )

    suspend fun backupOverviewResult(
        appNames: Map<String, String>
    ): Result<List<AppBackupSummary>> =
        repoOverview().map { buildSummaries(appNames, it) }

    private fun buildSummaries(
        appNames: Map<String, String>,
        overview: List<RepoOverview>
    ): List<AppBackupSummary> = overview.map { item ->
        val version = SnapshotMeta.version(item.tags)
        AppBackupSummary(
            packageName = item.packageName,
            count = item.count,
            latest = BackupEntry(
                title = appNames[item.packageName] ?: item.packageName,
                time = item.time,
                description = item.description,
                version = version,
                userName = SnapshotMeta.userName(item.tags),
                snapshots = item.snapshots
            ),
            hasIcon = item.hasIcon
        )
    }.sortedByDescending { it.latest.time }

    /**
     * 取某个包备份里的图标（应用没装时用）。
     *
     * 内部完成：找 `<包名>:icon` 快照 → 导出到临时目录 → 读出字节 → 清理。
     * 失败就说明没有图标，调用方不用再判断。
     */
    suspend fun loadIcon(packageName: String): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            val icon = listSnapshots(packageName)
                .getOrNull()
                ?.firstOrNull { it.label == "$packageName:${SnapshotKind.ICON.key}" }
                ?: return@withContext Result.failure(IllegalStateException("没有图标备份"))

            val dir = File(App.application.cacheDir, "icon_cache/${packageName.hashCode()}")
            dir.deleteRecursively()
            if (!dir.mkdirs()) {
                return@withContext Result.failure(IllegalStateException("无法创建缓存目录"))
            }

            try {
                val request = ExportSnapshotRequest(id = icon.id, dest = dir.absolutePath)
                val error = remoteCall { getService().exportSnapshot(request.toJsonText()) }
                if (!error.isNullOrBlank()) {
                    return@withContext Result.failure(remoteError(error))
                }

                val file = dir.listFiles()?.firstOrNull { it.isFile }
                    ?: return@withContext Result.failure(IllegalStateException("图标文件不存在"))

                Result.success(file.readBytes())
            } catch (t: Throwable) {
                Log.w(TAG, "loadIcon failed: $packageName", t)
                Result.failure(t)
            } finally {
                dir.deleteRecursively()
            }
        }

    /**
     * 删除当前包下的所有快照（含图标快照），返回删除数量。
     */
    suspend fun deleteAll(packageName: String): Result<Int> {
        // 按包名查（rustic 侧按 label 前缀过滤），不要拿全量列表自己筛：
        // 全量列表有条数上限，备份次数多了会漏掉老快照，导致「删不干净」
        val snapshots = listSnapshots(packageName).getOrElse {
            return Result.failure(it)
        }

        if (snapshots.isEmpty()) return Result.success(0)

        return deleteSnapshots(snapshots.map { it.id }).map { snapshots.size }
    }

    /**
     * 回收「备份已经删光、但 `包名:icon` 还留着」的孤儿图标快照。
     *
     * 图标快照不属于任何一次备份（没有时间戳分组），所以按备份删的时候碰不到它；
     * 应用的所有备份都删完之后需要单独清理，否则仓库里会一直留着这些垃圾。
     *
     * @return 清掉的图标快照数量
     */
    /**
     * 系统数据（Wi-Fi / 联系人 / 通话记录）各有多少条；读不到的类型不在结果里。
     * 三类都由 root 侧数（Wi-Fi 走 WifiManager，另外两类走 provider 的外部通道）。
     * 只是借 root 进程权限读本机数据，不碰仓库：用 [bindService] 只拿 binder，
     * 不走 [remoteCall]，也不注入当前仓库的凭据。
     */
    suspend fun systemDataCounts(): Map<SnapshotKind, Int> = withContext(Dispatchers.IO) {
        runCatching {
            val counts = bindService().systemDataCounts()
                .parseJson<SystemDataCountsResponse>().counts

            SystemDataExporter.SYSTEM_KINDS.mapNotNull { kind ->
                counts[kind.key]?.takeIf { it >= 0 }?.let { kind to it }
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    /** 打开 / 关闭 Wi-Fi；纯本机操作，不碰仓库，用 [bindService] 只拿 binder。 */
    suspend fun setWifiEnabled(enabled: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val error = bindService().setWifiEnabled(enabled)
            if (error.isNullOrBlank()) Result.success(Unit)
            else Result.failure(backendFailure("setWifiEnabled", error.orEmpty()))
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    suspend fun cleanOrphanIcons(): Result<Int> = withContext(Dispatchers.IO) {
        if (!AppPrefs.hasRepository()) return@withContext Result.success(0)

        try {
            val response = remoteCall { getService().cleanOrphanIcons(EMPTY_REQUEST) }
                .parseJson<CleanupResponse>()
            if (!response.ok) {
                return@withContext Result.failure(
                    remoteError(response.error.orEmpty().ifBlank { "清理孤立图标失败" })
                )
            }
            Result.success(response.removed)
        } catch (t: Throwable) {
            AppLog.e(TAG, "cleanOrphanIcons failed", t)
            Result.failure(t)
        }
    }

    /** 删除一行里勾选的快照。 */
    suspend fun deleteSelected(entry: BackupEntry, groups: Set<BackupGroup>): Result<Unit> {
        val ids = entry.selectedSnapshots(groups).map { it.id }
        if (ids.isEmpty()) {
            return Result.failure(IllegalStateException("请先勾选要删除的内容"))
        }
        return deleteSnapshots(ids)
    }

    /**
     * 回收仓库空间。
     *
     * rustic 删快照只是删引用，数据要 prune 才真正释放；删完备份后调一次。
     */
    /**
     * 整理仓库：先回收孤儿图标，再回收已删除备份占用的空间。
     *
     * 只有一个入口（设置页），所以固定做彻底清理（repack 所有半空的包）。
     * 顺序很重要：先删图标快照，它占的数据块才能在同一次 prune 里被回收。
     *
     * @return 清掉的孤儿图标数量（没有就是 0）
     */
    suspend fun prune(): Result<Int> = withContext(Dispatchers.IO) {
        try {
            // 删除孤儿图标后再整理空间；前一步失败（含测连失败）直接中断
            val removedIcons = cleanOrphanIcons().getOrElse {
                return@withContext Result.failure(it)
            }

            val error = remoteCall { getService().prune(EMPTY_REQUEST) }
            if (!error.isNullOrBlank()) {
                return@withContext Result.failure(remoteError(error))
            }

            Result.success(removedIcons)
        } catch (t: Throwable) {
            AppLog.e(TAG, "prune failed", t)
            Result.failure(t)
        }
    }

    /**
     * 只算不删：估算整理仓库能回收多少字节。
     *
     * 走一遍 `prune_plan`（读索引 + 算计划），比真正执行快得多。
     */
    suspend fun estimatePrune(): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val response = remoteCall { getService().pruneEstimate(EMPTY_REQUEST) }
                .parseJson<EstimateResponse>()
            if (response.ok) {
                Result.success(response.bytes)
            } else {
                Result.failure(
                    remoteError(response.error.orEmpty().ifBlank { "估算可回收空间失败" })
                )
            }
        } catch (t: Throwable) {
            AppLog.e(TAG, "prune estimate failed", t)
            Result.failure(t)
        }
    }

    /** 修改一行里所有快照的备注；description 为 null 表示移除。 */
    suspend fun updateEntryDescription(entry: BackupEntry, description: String?): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val request = UpdateDescriptionRequest(ids = entry.ids, description = description)
                val error = remoteCall { getService().updateDescription(request.toJsonText()) }
                if (error.isNullOrBlank()) {
                    Result.success(Unit)
                } else {
                    Result.failure(remoteError(error))
                }
            } catch (t: Throwable) {
                AppLog.e(TAG, "updateEntryDescription failed", t)
                Result.failure(t)
            }
        }

    /** 覆盖备份按钮是否展示：应用没装就不允许覆盖。 */
    fun canOverwrite(installed: Boolean): Boolean = installed

    /** 普通备份。 */
    suspend fun backup(
        app: AppInfo,
        groups: Set<BackupGroup>,
        onProgress: (BackupProgress) -> Unit = {},
        /** 一次逻辑操作的标识：重试沿用，主动新备份换新值。 */
        operationId: Long = System.currentTimeMillis(),
        /**
         * 批量备份的批次标识：同一批里每个应用传同一个值。
         *
         * 仓库只会在这一批的第一次打开（scrypt + 读索引 563ms），后面的应用直接复用。
         * 单独备份不传，行为和原来完全一样。
         */
        batchId: String? = null,
        /**
         * 仓库里是否已经有这个应用的图标快照。
         *
         * 批量备份时由列表页的备份概况直接给，省掉每个应用一次「查这个包的快照」的 IPC
         * （那是一次全仓库快照扫描）。传 null 就现场查一次。
         */
        iconAlreadyBackedUp: Boolean? = null,
        /**
         * 类型 -> 上一次同类快照的 id（来自备份概况）。
         *
         * 传给 rustic 当 parent：有了它就不用再列一遍全部快照去找 parent。
         * 传 null 就退回 rustic 的默认行为。
         */
        parentIds: Map<SnapshotKind, String>? = null
    ): Result<Unit> {
        if (groups.isEmpty()) {
            return Result.failure(IllegalStateException("请先选择要备份的内容"))
        }
        val result = withAppPaused(app) {
            runBackup(
                request(app, groups),
                onProgress = onProgress,
                batchId = batchId,
                operationId = operationId,
                iconAlreadyBackedUp = iconAlreadyBackedUp,
                parentIds = parentIds
            )
        }
        // 批量备份（batchId 有值）留到整批结束后由调用方统一做一次 —— 目标始终是同一个
        if (result.isSuccess && batchId == null) exportSelfApk()
        return result
    }

    /**
     * 覆盖备份：沿用旧快照的 label / tag / 备注，新的写成功了再删掉旧的。
     *
     * @param batchId 批量覆盖的批次标识：同一批里每个应用传同一个值，仓库只打开一次。
     *   和 [backup] 一样，传了它就由调用方在整批结束后统一导出自身安装包。
     */
    suspend fun overwrite(
        app: AppInfo,
        entry: BackupEntry,
        groups: Set<BackupGroup>,
        onProgress: (BackupProgress) -> Unit = {},
        batchId: String? = null,
        /** 一次逻辑操作的标识：重试沿用，主动新备份换新值。 */
        operationId: Long = System.currentTimeMillis()
    ): Result<Unit> {
        val selected = entry.selectedSnapshots(groups)
        if (selected.isEmpty()) {
            return Result.failure(IllegalStateException("请先勾选要覆盖的内容"))
        }

        val overrides = selected.mapNotNull { snapshot ->
            snapshot.kind()?.let {
                it.key to SnapshotOverride(snapshot.label, snapshot.tags, snapshot.description)
            }
        }.toMap()
        // user_de / permissions 快照由 user 这一步产生，备份请求里按 user 传
        val groupsToBackup = selected.mapNotNull { it.kind() }.mapNotNull { kind ->
            BackupGroup.entries.firstOrNull { kind in it.kinds }
        }.toSet()

        val result = withAppPaused(app) {
            runBackup(
                request = request(app, groupsToBackup),
                overrides = overrides,
                deleteIds = selected.map { it.id },
                onProgress = onProgress,
                batchId = batchId,
                operationId = operationId
            )
        }
        // 覆盖备份也是备份：本程序可能已经升级过，仓库里那份得跟着换。
        // 批量覆盖（batchId 有值）同样留到整批结束后统一做一次。
        if (result.isSuccess && batchId == null) exportSelfApk()
        return result
    }

    /**
     * 备份期间暂停目标应用：给它所有进程发 SIGSTOP，文件就不会再被改写。
     *
     * 备份结束（成功或失败）立刻 SIGCONT 恢复；同时记一份到 AppPrefs，
     * 万一进程被杀，下次启动会把残留的暂停恢复掉。
     */
    private suspend fun <T> withAppPaused(app: AppInfo, block: suspend () -> T): T {
        // 系统数据不是应用，没有进程可停
        if (BackupTarget.of(app).isFixed) return block()

        val key = "${app.packageName}|${app.userId}"
        val paused = AppManagerService.pauseApp(app.packageName, app.userId)
        if (paused) AppPrefs.addPausedApp(key)

        return try {
            block()
        } finally {
            if (paused) {
                AppManagerService.resumeApp(app.packageName, app.userId)
                AppPrefs.removePausedApp(key)
            }
        }
    }

    /**
     * 把本程序自己的安装包复制到仓库根目录，方便从仓库里直接装回来。
     *
     * 只在「备份」类操作里调：普通备份和覆盖备份都算，恢复 / 删除不调。什么时候调：
     * - 单个应用备份 / 覆盖成功 → [backup] / [overwrite] 内部立刻调一次；
     * - 批量备份 / 覆盖 → 整批结束后由调用方调一次（见 `BackupBatchScreen.runBatch`）。
     *
     * 受 [AppPrefs.backupSelf] 控制；仓库路径为空时直接返回。
     */
    suspend fun exportSelfApk() = withContext(Dispatchers.IO) {
        if (!AppPrefs.backupSelf) return@withContext
        // 远程仓库没有「根目录」可以 cp，跳过（不影响备份主流程）
        if (AppPrefs.isRemote) return@withContext
        val dir = repoPath
        if (dir.isBlank()) return@withContext

        runCatching {
            val source = App.application.applicationInfo.sourceDir
            val dest = "$dir/app.apk"
            val result = Shell.cmd("cp -f \"$source\" \"$dest\"").exec()
            if (result.isSuccess) {
                Log.d(TAG, "copySelfApk: $dest")
            } else {
                Log.w(TAG, "copySelfApk failed: ${result.err.joinToString()}")
            }
        }.onFailure { AppLog.w(TAG, "copySelfApk 失败（不影响备份主流程）", it) }
    }

    /**
     * 恢复前的检查：返回要提示给用户的原因，null 表示可以恢复。
     */
    fun restoreBlockedReason(
        entry: BackupEntry,
        groups: Set<BackupGroup>,
        installed: Boolean
    ): String? {
        val selected = entry.selectedSnapshots(groups)
        if (selected.isEmpty()) return "请先勾选要恢复的内容"

        // 目录和系统数据（Wi-Fi / 联系人 / 通话记录）都不依赖应用装没装
        if (selected.all {
                it.kind() == SnapshotKind.DIR || it.kind() in SystemDataExporter.SYSTEM_KINDS
            }
        ) {
            return null
        }

        val hasApk = selected.any { it.kind() == SnapshotKind.APK }
        val needsApp = selected.any { it.kind() != SnapshotKind.APK }
        return if (needsApp && !hasApk && !installed) "应用未安装，无法恢复数据" else null
    }

    /**
     * 恢复一行里勾选的快照。
     *
     * @param installed 应用当前是否已安装（未安装且没勾 apk 时无法恢复数据）
     */
    suspend fun restore(
        app: AppInfo,
        entry: BackupEntry,
        groups: Set<BackupGroup>,
        installed: Boolean,
        onProgress: (BackupProgress) -> Unit = {}
    ): Result<Unit> {
        val selected = entry.selectedSnapshots(groups)
        restoreBlockedReason(entry, groups, installed)?.let {
            return Result.failure(IllegalStateException(it))
        }

        // 恢复前先停掉应用：一边写一边被覆盖会拿到半新半旧的数据
        // （目录 / 系统数据不是应用，没有进程可停）
        if (BackupTarget.of(app) == BackupTarget.APP) {
            AppManagerService.stopQuietly(app.packageName, app.userId)
        }

        return withContext(Dispatchers.IO) {
            val op = AppLog.startOperation("restore", app.packageName)
            val startedAt = System.currentTimeMillis()
            try {
                val request = RestoreSnapshotsRequest(
                    op = op,
                    userId = app.userId,
                    packageName = app.packageName,
                    delete = true,
                    items = selected.mapNotNull { snapshot ->
                        snapshot.kind()?.let { kind ->
                            RestoreKindItem(kind = kind.key, id = snapshot.id)
                        }
                    }
                )
                val callback = object : IBackupProgressCallback.Stub() {
                    override fun onProgress(progress: String) {
                        runCatching { onProgress(progress.parseJson<BackupProgress>()) }
                    }
                }

                val error = remoteCall {
                    getService().restoreSnapshots(request.toJsonText(), callback)
                }
                if (!error.isNullOrBlank()) {
                    AppLog.finishOperation(op, "restore", app.packageName, startedAt, "failed: $error")
                    return@withContext Result.failure(remoteError(error))
                }

                AppLog.finishOperation(
                    op, "restore", app.packageName, startedAt,
                    "ok items=${selected.size}"
                )
                Result.success(Unit)
            } catch (t: Throwable) {
                AppLog.e(TAG, "restore failed", t, op)
                AppLog.finishOperation(op, "restore", app.packageName, startedAt, "failed: ${t.message}")
                Result.failure(remoteFailure(t))
            }
        }
    }

    // ==================== 进度步骤 ====================

    /**
     * 进度弹窗的步骤顺序：目录 → apk → obb → 权限 → user → user_de → data。
     *
     * 系统数据（Wi-Fi / 联系人 / 通话记录）两种模式都排在最后 —— 它们各自一个快照，
     * 不和应用的那几步混在一起。
     */
    private val STEP_ORDER = listOf(
        SnapshotKind.DIR,
        SnapshotKind.APK,
        SnapshotKind.OBB,
        SnapshotKind.PERMISSIONS,
        SnapshotKind.USER,
        SnapshotKind.USER_DE,
        SnapshotKind.DATA,
        SnapshotKind.WIFI,
        SnapshotKind.CONTACTS,
        SnapshotKind.CALLS
    )

    /** 备份要显示的步骤（顺序固定）。 */
    fun backupSteps(groups: Set<BackupGroup>): List<BackupStep> =
        STEP_ORDER.filter { kind -> groups.any { it.covers(kind) } }
            .map { BackupStep(it, BackupStepStatus.WAITING) }

    /** 恢复要显示的步骤：只列出真正勾选到的快照。 */
    fun restoreSteps(entry: BackupEntry, groups: Set<BackupGroup>): List<BackupStep> {
        val kinds = entry.selectedSnapshots(groups).mapNotNull { it.kind() }.toSet()
        return STEP_ORDER.filter { it in kinds }.map { BackupStep(it, BackupStepStatus.WAITING) }
    }

    /** 把一条进度事件合并进步骤列表。 */
    fun applyProgress(steps: List<BackupStep>, progress: BackupProgress): List<BackupStep> {
        if (progress.stage.isBlank()) return steps
        return steps.map { step ->
            if (step.kind.key != progress.stage) {
                step
            } else {
                step.copy(
                    status = statusOf(progress.state, step.status),
                    files = progress.files,
                    reason = progress.reason
                )
            }
        }
    }

    /** 失败时把还没跑完的步骤标成失败。 */
    fun markFailed(steps: List<BackupStep>): List<BackupStep> = steps.map {
        if (it.status == BackupStepStatus.WAITING || it.status == BackupStepStatus.RUNNING) {
            it.copy(status = BackupStepStatus.FAILED)
        } else {
            it
        }
    }

    // ==================== 内部 ====================

    private fun request(
        app: AppInfo,
        groups: Set<BackupGroup>
    ) = BackupRequest(
        packageName = app.packageName,
        appName = app.name,
        version = app.version,
        userId = app.userId,
        // obb 和权限都跟着 apk 走（apk 是「这个应用是什么」，user / data 是「它存了什么」）
        backupApk = BackupGroup.APK in groups,
        backupObb = BackupGroup.APK in groups,
        backupUser = BackupGroup.USER in groups,
        backupData = BackupGroup.DATA in groups,
        backupPermissions = BackupGroup.APK in groups
    )

    private fun statusOf(state: String, fallback: BackupStepStatus): BackupStepStatus =
        when (state) {
            "running" -> BackupStepStatus.RUNNING
            "done" -> BackupStepStatus.DONE
            "skipped" -> BackupStepStatus.SKIPPED
            else -> fallback
        }

    /** 快照 -> 备份行：去掉 icon，同一次备份（时间戳相同）的合成一行。 */
    private fun List<BackupSnapshot>.toEntries(appName: String): List<BackupEntry> =
        asSequence()
            .filterNot { it.kind() == SnapshotKind.ICON }
            .groupBy { SnapshotMeta.timestamp(it.tags) ?: it.time }
            .map { (time, group) ->
                val version = group.firstNotNullOfOrNull { SnapshotMeta.version(it.tags) }
                BackupEntry(
                    title = appName,
                    time = time,
                    description = group.firstNotNullOfOrNull { it.description },
                    version = version,
                    userName = group.firstNotNullOfOrNull { SnapshotMeta.userName(it.tags) },
                    snapshots = group
                )
            }
            .sortedByDescending { it.time }
            .toList()
}
