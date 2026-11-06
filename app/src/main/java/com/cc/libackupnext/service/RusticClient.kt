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
import com.cc.libackupnext.data.RepoOverview
import com.cc.libackupnext.data.SnapshotKind
import com.cc.libackupnext.data.SnapshotMeta
import com.cc.libackupnext.data.SnapshotOverride
import com.cc.libackupnext.data.kind
import com.cc.libackupnext.entity.AppInfo
import com.cc.libackupnext.service.util.SystemDataExporter
import com.cc.libackupnext.util.AppLog
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 业务层：主进程直接用的入口。
 *
 * 和 [RusticServer]（root 侧）分开放 —— 那边是 `RootService` + JNI，
 * 这边是绑定服务、拼请求、把结果整理成 UI 能直接渲染的模型。
 */
object RusticClient {

    private const val TAG = "Rustic"

    private val manager by lazy {
        RootServiceManager<IRusticService>(RusticServer.Service::class.java) {
            IRusticService.Stub.asInterface(it)
        }
    }

    /** 不含凭据的空请求：仓库地址和密码只由 setRepo() 注入。 */
    private const val EMPTY_REQUEST = "{}"

    // ==================== 仓库凭据 ====================
    //
    // AppPrefs 是唯一真源，Rust 侧有一份静态副本；root 侧 Kotlin 不留密码。
    // 每次绑定（含服务被杀后重连）通过 setRepo() 注入一次，之后所有业务命令
    // 都不带凭据。用 binder 身份 + 版本号判断是否需要重新注入，正常路径零额外 IPC。
    // initRepo() 是唯一自带候选凭据的命令：只校验 / 建库，不改全局配置，
    // 成功才由 initRepository() 保存 AppPrefs 并作废注入标记。

    private val credentialLock = Mutex()

    @Volatile
    private var credentialVersion = 0L
    private var injectedBinder: IBinder? = null
    private var injectedVersion = -1L

    /** 凭据需要重新对齐（初始化成功 / 服务重启）：下次调用重新注入真源。 */
    fun invalidateCredentials() {
        credentialVersion++
    }

    private suspend fun getService(): IRusticService {
        val service = manager.getService()
        credentialLock.withLock {
            val binder = service.asBinder()
            if (injectedBinder !== binder || injectedVersion != credentialVersion) {
                // 只记「注入了凭据」，绝不能把密码写进日志
                AppLog.i(TAG, "绑定 root 服务，注入仓库凭据")
                service.setRepo(repoPath, repoPassword)
                injectedBinder = binder
                injectedVersion = credentialVersion
            }
        }
        return service
    }

    /** 提前绑定 root 服务，避免点初始化时再等 su 握手。 */
    suspend fun warmUp() {
        runCatching { getService() }
            .onFailure { AppLog.w(TAG, "warmUp 失败（root 服务暂不可用）", it) }
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
     */
    suspend fun repoOverview(): List<RepoOverview> = withContext(Dispatchers.IO) {
        if (!AppPrefs.hasRepository()) return@withContext emptyList()

        try {
            val json = JSONObject(getService().overview(EMPTY_REQUEST))
            if (!json.optBoolean("ok", false)) {
                AppLog.e(
                    "备份概况",
                    json.optString("error").ifBlank { "读取备份概况失败" }
                )
                return@withContext emptyList()
            }

            val array = json.optJSONArray("packages") ?: JSONArray()
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val packageName = item.optString("package")
                    if (packageName.isBlank()) continue

                    val tags = item.optString("tags")
                    val time = item.optLong("time")
                    val description = item.optString("description").takeIf { it.isNotBlank() }

                    val snapshots = item.optJSONArray("snapshots") ?: JSONArray()
                    add(
                        RepoOverview(
                            packageName = packageName,
                            count = item.optInt("count"),
                            time = time,
                            tags = tags,
                            description = description,
                            snapshots = buildList {
                                for (i in 0 until snapshots.length()) {
                                    val snapshot = snapshots.getJSONObject(i)
                                    add(
                                        BackupSnapshot(
                                            id = snapshot.optString("id"),
                                            label = snapshot.optString("label"),
                                            // 同一次备份里这些字段都一样，Rust 侧只回传一份
                                            tags = tags,
                                            time = time,
                                            size = snapshot.optLong("size"),
                                            description = description
                                        )
                                    )
                                }
                            },
                            hasApk = item.optBoolean("has_apk"),
                            apkTags = item.optString("apk_tags").takeIf { it.isNotBlank() },
                            apkTime = item.optLong("apk_time"),
                            hasIcon = item.optBoolean("has_icon")
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            AppLog.e(TAG, "repoOverview failed", t)
            emptyList()
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
            val request = JSONObject().apply {
                put("package_name", packageName.orEmpty())
            }

            val json = JSONObject(getService().listSnapshots(request.toString()))
            if (!json.optBoolean("ok", false)) {
                return@withContext Result.failure(
                    IllegalStateException(json.optString("error").ifBlank { "查询快照失败" })
                )
            }

            val array = json.optJSONArray("snapshots") ?: JSONArray()
            val snapshots = buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        BackupSnapshot(
                            id = item.optString("id"),
                            label = item.optString("label"),
                            tags = item.optString("tags"),
                            time = item.optLong("time"),
                            size = item.optLong("size"),
                            description = item.optString("description").takeIf { it.isNotBlank() }
                        )
                    )
                }
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
            val request = JSONObject().apply {
                put("ids", JSONArray(ids))
            }
            val error = getService().deleteSnapshots(request.toString())
            if (error.isNullOrBlank()) Result.success(Unit)
            else Result.failure(IllegalStateException(error))
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
        iconAlreadyBackedUp: Boolean? = null,
        parentIds: Map<SnapshotKind, String>? = null,
        onProgress: (BackupProgress) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val op = AppLog.startOperation("backup", request.packageName)
        val startedAt = System.currentTimeMillis()
        try {
            val callback = object : IBackupProgressCallback.Stub() {
                override fun onProgress(progress: String) {
                    runCatching {
                        val json = JSONObject(progress)
                        onProgress(
                            BackupProgress(
                                stage = json.optString("stage"),
                                state = json.optString("state"),
                                files = json.optLong("files"),
                                reason = json.optString("reason")
                            )
                        )
                    }
                }
            }

            // 目录 / 系统数据都没有图标，跳过这一步（系统数据也就不用去仓库里查了）
            val isDir = request.packageName.startsWith('/')
            val isFixed = request.packageName in BackupTarget.fixedIds

            // 图标只保留一份：仓库里已有 pkg:icon 就不再备份。
            // 批量备份时调用方已经知道答案（来自备份概况），不用再查一遍。
            val hasIcon = isDir || isFixed || iconAlreadyBackedUp ?: listSnapshots(
                packageName = request.packageName
            ).getOrNull()?.any { it.label == "${request.packageName}:${SnapshotKind.ICON.key}" }
                ?: false

            val payload = JSONObject().apply {
                // 透传到 :root，两侧日志用同一个 op 串起来
                put("op", op)
                put("packageName", request.packageName)
                put("appName", request.appName)
                put("version", request.version)
                put("userId", request.userId)
                // 这些 key 就是 RusticRequestBuilder 里 optBoolean 读的那些
                put(SnapshotKind.APK.key, request.backupApk)
                put(SnapshotKind.OBB.key, request.backupObb)
                put(SnapshotKind.USER.key, request.backupUser)
                put(SnapshotKind.DATA.key, request.backupData)
                put(SnapshotKind.PERMISSIONS.key, request.backupPermissions)
                put("timestamp", System.currentTimeMillis())
                put("includeIcon", !hasIcon && !isDir)
                if (!parentIds.isNullOrEmpty()) put("parents", JSONObject(parentIds.mapKeys { it.key.key }))
                // 同批次的多个应用共用一次「打开仓库 + 读索引」
                if (!batchId.isNullOrBlank()) put("batch", batchId)
                if (overrides.isNotEmpty()) {
                    put(
                        "overrides",
                        JSONObject().apply {
                            overrides.forEach { (kind, override) ->
                                put(
                                    kind,
                                    JSONObject().apply {
                                        put("label", override.label)
                                        put("tags", override.tags)
                                        if (override.description != null) {
                                            put("description", override.description)
                                        }
                                    }
                                )
                            }
                        }
                    )
                }
            }

            val error = getService().backup(payload.toString(), callback)
            if (!error.isNullOrBlank()) {
                AppLog.finishOperation(op, "backup", request.packageName, startedAt, "failed: $error")
                return@withContext Result.failure(IllegalStateException(error))
            }

            // 覆盖备份：新的写成功了再删旧的
            if (deleteIds.isNotEmpty()) {
                val deleteRequest = JSONObject().apply {
                    put("ids", JSONArray(deleteIds))
                }
                val deleteError = getService().deleteSnapshots(deleteRequest.toString())
                if (!deleteError.isNullOrBlank()) {
                    AppLog.finishOperation(
                        op, "backup", request.packageName, startedAt,
                        "failed: 删除旧快照 $deleteError"
                    )
                    return@withContext Result.failure(IllegalStateException(deleteError))
                }
            }

            AppLog.finishOperation(op, "backup", request.packageName, startedAt, "ok")
            Result.success(Unit)
        } catch (t: Throwable) {
            AppLog.e(TAG, "backup failed", t, op)
            AppLog.finishOperation(op, "backup", request.packageName, startedAt, "failed: ${t.message}")
            Result.failure(t)
        }
    }

    // ==================== 业务层 ====================
    // 下面是对外真正使用的方法：读 AppPrefs 里的仓库信息，做业务判断和数据处理。
    // UI 只拿这些方法的返回值渲染，不再自己拼请求、分组快照或判断勾选。

    private val repoPath: String get() = AppPrefs.repoPath
    private val repoPassword: String get() = AppPrefs.repoPassword

    /**
     * 初始化 / 校验 rustic 仓库，成功后才记住地址和密码。
     *
     * 候选路径和密码只随 initRepo() 这一次请求下传，不写全局配置。
     * 因此失败时 AppPrefs 保持原样，root / Rust 侧也没被改过，不需要任何回滚。
     *
     * @return 成功返回 Unit；失败返回带具体原因的错误。
     */
    suspend fun initRepository(path: String, password: String): Result<Unit> {
        // 空目录 / 空密码都不该建仓库：空密码等于把备份完全敞开
        if (path.isBlank()) {
            return Result.failure(IllegalStateException("请先选择仓库目录"))
        }
        if (password.isBlank()) {
            return Result.failure(IllegalStateException("请先输入仓库密码"))
        }

        return withContext(Dispatchers.IO) {
            val op = AppLog.startOperation("initRepo", path)
            val startedAt = System.currentTimeMillis()
            try {
                val error = getService().initRepo(path, password)
                if (error.isNullOrBlank()) {
                    AppPrefs.saveRepository(path, password)
                    // 成功才让下一次 getService() 把新凭据注入到 root / Rust 侧
                    invalidateCredentials()
                    AppLog.finishOperation(op, "initRepo", path, startedAt, "ok")
                    Result.success(Unit)
                } else {
                    AppLog.finishOperation(op, "initRepo", path, startedAt, "failed: $error")
                    Result.failure(IllegalStateException(error))
                }
            } catch (t: Throwable) {
                AppLog.e(TAG, "initRepository failed", t, op)
                AppLog.finishOperation(op, "initRepo", path, startedAt, "failed: ${t.message}")
                Result.failure(t)
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
        return (overview ?: repoOverview())
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
    ): List<AppBackupSummary> =
        (overview ?: repoOverview()).map { overview ->
            val version = SnapshotMeta.version(overview.tags)
            AppBackupSummary(
                packageName = overview.packageName,
                count = overview.count,
                latest = BackupEntry(
                    title = appNames[overview.packageName] ?: overview.packageName,
                    time = overview.time,
                    description = overview.description,
                    version = version,
                    userName = SnapshotMeta.userName(overview.tags),
                    snapshots = overview.snapshots
                ),
                hasIcon = overview.hasIcon
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
                val request = JSONObject().apply {
                    put("id", icon.id)
                    put("dest", dir.absolutePath)
                }
                val error = getService().exportSnapshot(request.toString())
                if (!error.isNullOrBlank()) {
                    return@withContext Result.failure(IllegalStateException(error))
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
     */
    suspend fun systemDataCounts(): Map<SnapshotKind, Int> = withContext(Dispatchers.IO) {
        runCatching {
            val counts = JSONObject(getService().systemDataCounts()).optJSONObject("counts")
                ?: return@runCatching emptyMap<SnapshotKind, Int>()

            SystemDataExporter.SYSTEM_KINDS.mapNotNull { kind ->
                counts.optInt(kind.key, -1).takeIf { it >= 0 }?.let { kind to it }
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    /** 打开 / 关闭 Wi-Fi */
    suspend fun setWifiEnabled(enabled: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val error = getService().setWifiEnabled(enabled)
            if (error.isNullOrBlank()) Result.success(Unit)
            else Result.failure(IllegalStateException(error))
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    suspend fun cleanOrphanIcons(): Result<Int> = withContext(Dispatchers.IO) {
        if (!AppPrefs.hasRepository()) return@withContext Result.success(0)

        try {
            val json = JSONObject(getService().cleanOrphanIcons(EMPTY_REQUEST))
            if (!json.optBoolean("ok", false)) {
                return@withContext Result.failure(
                    IllegalStateException(
                        json.optString("error").ifBlank { "清理孤立图标失败" }
                    )
                )
            }
            Result.success(json.optInt("removed"))
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
            // 删除孤儿图标后再整理空间
            val removedIcons = cleanOrphanIcons().getOrDefault(0)

            val error = getService().prune(EMPTY_REQUEST)
            if (!error.isNullOrBlank()) {
                return@withContext Result.failure(IllegalStateException(error))
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
            val json = JSONObject(getService().pruneEstimate(EMPTY_REQUEST))
            if (json.optBoolean("ok", false)) {
                Result.success(json.optLong("bytes", 0L))
            } else {
                Result.failure(
                    IllegalStateException(
                        json.optString("error").ifBlank { "估算可回收空间失败" }
                    )
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
                val request = JSONObject().apply {
                    put("ids", JSONArray(entry.ids))
                    if (description != null) {
                        put("description", description)
                    }
                }

                val error = getService().updateDescription(request.toString())
                if (error.isNullOrBlank()) {
                    Result.success(Unit)
                } else {
                    Result.failure(IllegalStateException(error))
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
        batchId: String? = null
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
                batchId = batchId
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
                val request = JSONObject().apply {
                    put("op", op)
                    put("userId", app.userId)
                    put("packageName", app.packageName)
                    put("delete", true)
                    put(
                        "items",
                        JSONArray().apply {
                            selected.forEach { snapshot ->
                                snapshot.kind()?.let { kind ->
                                    put(JSONObject().put("kind", kind.key).put("id", snapshot.id))
                                }
                            }
                        }
                    )
                }
                val callback = object : IBackupProgressCallback.Stub() {
                    override fun onProgress(progress: String) {
                        runCatching {
                            val json = JSONObject(progress)
                            onProgress(
                                BackupProgress(
                                    stage = json.optString("stage"),
                                    state = json.optString("state"),
                                    files = json.optLong("files"),
                                    reason = json.optString("reason")
                                )
                            )
                        }
                    }
                }

                val error = getService().restoreSnapshots(request.toString(), callback)
                if (!error.isNullOrBlank()) {
                    AppLog.finishOperation(op, "restore", app.packageName, startedAt, "failed: $error")
                    return@withContext Result.failure(IllegalStateException(error))
                }

                AppLog.finishOperation(
                    op, "restore", app.packageName, startedAt,
                    "ok items=${selected.size}"
                )
                Result.success(Unit)
            } catch (t: Throwable) {
                AppLog.e(TAG, "restore failed", t, op)
                AppLog.finishOperation(op, "restore", app.packageName, startedAt, "failed: ${t.message}")
                Result.failure(t)
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
