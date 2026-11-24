package com.cc.libackupnext.service.gateway

import com.cc.libackupnext.data.AppInfo
import com.cc.libackupnext.data.BackupEntry
import com.cc.libackupnext.data.BackupGroup
import com.cc.libackupnext.data.BackupProgress
import com.cc.libackupnext.data.BackupRequest
import com.cc.libackupnext.data.BackupSnapshot
import com.cc.libackupnext.data.BackupTarget
import com.cc.libackupnext.data.SnapshotKind
import com.cc.libackupnext.data.SnapshotOverride
import com.cc.libackupnext.data.json.BackupOptions
import com.cc.libackupnext.data.json.CleanupResponse
import com.cc.libackupnext.data.json.DeleteSnapshotsRequest
import com.cc.libackupnext.data.json.EstimateResponse
import com.cc.libackupnext.data.json.RestoreKindItem
import com.cc.libackupnext.data.json.RestoreSnapshotsRequest
import com.cc.libackupnext.data.json.SnapshotOverrideDto
import com.cc.libackupnext.data.json.UpdateDescriptionRequest
import com.cc.libackupnext.data.kind
import com.cc.libackupnext.data.prefs.BackupPrefs
import com.cc.libackupnext.data.prefs.RepoPrefs
import com.cc.libackupnext.service.IBackupProgressCallback
import com.cc.libackupnext.service.gateway.RusticSession.Companion.EMPTY_REQUEST
import com.cc.libackupnext.service.gateway.RusticSession.Companion.TAG
import com.cc.libackupnext.service.json.parseJson
import com.cc.libackupnext.service.json.toJsonText
import com.cc.libackupnext.util.AppLog
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 仓库的写入实现。
 *
 * 每个方法对应**一次 IPC**，参数就是这次命令需要的东西。
 * 不判断「该不该写」、不做暂停 / 清理 / 导出 —— 那些在 `service/usecase`。
 */
internal class RusticWriterGateway(
    private val session: RusticSession,
    private val reader: RepoReader,
    private val repoPrefs: RepoPrefs,
    private val backupPrefs: BackupPrefs,
) : RepoWriter {

    override suspend fun write(
        request: BackupRequest,
        overrides: Map<String, SnapshotOverride>,
        deleteIds: List<String>,
        batchId: String?,
        operationId: Long,
        iconAlreadyBackedUp: Boolean?,
        parentIds: Map<SnapshotKind, String>?,
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
            val hasIcon = isDir || isFixed || iconAlreadyBackedUp ?: reader.listSnapshots(
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
                kinds = request.kinds,
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

            val reply = session.remote.call {
                session.service().backup(payload.toJsonText(), callback)
            }
            parseRusticReply(reply)?.let { failure ->
                AppLog.finishOperation(
                    op, "backup", request.packageName, startedAt, "failed: ${failure.message}"
                )
                return@withContext Result.failure(session.remote.error(failure))
            }

            // 覆盖备份：新的写成功了再删旧的
            if (deleteIds.isNotEmpty()) {
                val deleteRequest = DeleteSnapshotsRequest(ids = deleteIds)
                val deleteFailure = parseRusticReply(
                    session.remote.call {
                        session.service().deleteSnapshots(deleteRequest.toJsonText())
                    }
                )
                if (deleteFailure != null) {
                    AppLog.finishOperation(
                        op, "backup", request.packageName, startedAt,
                        "failed: 删除旧快照 ${deleteFailure.message}"
                    )
                    return@withContext Result.failure(session.remote.error(deleteFailure))
                }
            }

            AppLog.finishOperation(op, "backup", request.packageName, startedAt, "ok")
            Result.success(Unit)
        } catch (t: Throwable) {
            AppLog.e(TAG, "backup failed", t, op)
            AppLog.finishOperation(op, "backup", request.packageName, startedAt, "failed: ${t.message}")
            Result.failure(session.remote.failure(t))
        }
    }

    override suspend fun restoreSnapshots(
        app: AppInfo,
        snapshots: List<BackupSnapshot>,
        onProgress: (BackupProgress) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val op = AppLog.startOperation("restore", app.packageName)
        val startedAt = System.currentTimeMillis()
        try {
            val request = RestoreSnapshotsRequest(
                op = op,
                userId = app.userId,
                packageName = app.packageName,
                delete = true,
                items = snapshots.mapNotNull { snapshot ->
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

            val failure = parseRusticReply(
                session.remote.call {
                    session.service().restoreSnapshots(request.toJsonText(), callback)
                }
            )
            if (failure != null) {
                AppLog.finishOperation(
                    op, "restore", app.packageName, startedAt, "failed: ${failure.message}"
                )
                return@withContext Result.failure(session.remote.error(failure))
            }

            AppLog.finishOperation(
                op, "restore", app.packageName, startedAt,
                "ok items=${snapshots.size}"
            )
            Result.success(Unit)
        } catch (t: Throwable) {
            AppLog.e(TAG, "restore failed", t, op)
            AppLog.finishOperation(op, "restore", app.packageName, startedAt, "failed: ${t.message}")
            Result.failure(session.remote.failure(t))
        }
    }

    override suspend fun deleteSnapshots(ids: List<String>): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val request = DeleteSnapshotsRequest(ids = ids)
                val failure = parseRusticReply(
                    session.remote.call {
                        session.service().deleteSnapshots(request.toJsonText())
                    }
                )
                if (failure == null) Result.success(Unit)
                else Result.failure(session.remote.error(failure))
            } catch (t: Throwable) {
                AppLog.e(TAG, "deleteSnapshots failed", t)
                Result.failure(t)
            }
        }

    override suspend fun deleteSelected(
        entry: BackupEntry,
        groups: Set<BackupGroup>
    ): Result<Unit> {
        val ids = entry.selectedSnapshots(groups).map { it.id }
        if (ids.isEmpty()) {
            return Result.failure(IllegalStateException("请先勾选要删除的内容"))
        }
        return deleteSnapshots(ids)
    }

    override suspend fun deleteAll(packageName: String): Result<Int> {
        // 按包名查（rustic 侧按 label 前缀过滤），不要拿全量列表自己筛：
        // 全量列表有条数上限，备份次数多了会漏掉老快照，导致「删不干净」
        val snapshots = reader.listSnapshots(packageName).getOrElse {
            return Result.failure(it)
        }

        if (snapshots.isEmpty()) return Result.success(0)

        return deleteSnapshots(snapshots.map { it.id }).map { snapshots.size }
    }

    override suspend fun updateEntryDescription(
        entry: BackupEntry,
        description: String?
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val request = UpdateDescriptionRequest(ids = entry.ids, description = description)
            val failure = parseRusticReply(
                session.remote.call { session.service().updateDescription(request.toJsonText()) }
            )
            if (failure == null) {
                Result.success(Unit)
            } else {
                Result.failure(session.remote.error(failure))
            }
        } catch (t: Throwable) {
            AppLog.e(TAG, "updateEntryDescription failed", t)
            Result.failure(t)
        }
    }

    /**
     * 整理仓库：先回收孤儿图标，再回收已删除备份占用的空间。
     *
     * 只有一个入口（设置页），所以固定做彻底清理（repack 所有半空的包）。
     * 顺序很重要：先删图标快照，它占的数据块才能在同一次 prune 里被回收。
     *
     * @return 清掉的孤儿图标数量（没有就是 0）
     */
    override suspend fun prune(): Result<Int> = withContext(Dispatchers.IO) {
        try {
            // 删除孤儿图标后再整理空间；前一步失败（含测连失败）直接中断
            val removedIcons = cleanOrphanIcons().getOrElse {
                return@withContext Result.failure(it)
            }

            val failure = parseRusticReply(session.remote.call { session.service().prune(EMPTY_REQUEST) })
            if (failure != null) {
                return@withContext Result.failure(session.remote.error(failure))
            }

            Result.success(removedIcons)
        } catch (t: Throwable) {
            AppLog.e(TAG, "prune failed", t)
            Result.failure(t)
        }
    }

    /**
     * 回收「备份已经删光、但 `包名:icon` 还留着」的孤儿图标快照。
     *
     * 图标快照不属于任何一次备份（没有时间戳分组），所以按备份删的时候碰不到它；
     * 应用的所有备份都删完之后需要单独清理，否则仓库里会一直留着这些垃圾。
     */
    private suspend fun cleanOrphanIcons(): Result<Int> = withContext(Dispatchers.IO) {
        if (!repoPrefs.hasRepository()) return@withContext Result.success(0)

        try {
            val response = session.remote.call {
                session.service().cleanOrphanIcons(EMPTY_REQUEST)
            }.parseJson<CleanupResponse>()
            if (!response.ok) {
                return@withContext Result.failure(
                    session.remote.error(
                        failureOf(response.error, response.reason, "清理孤立图标失败")
                    )
                )
            }
            Result.success(response.removed)
        } catch (t: Throwable) {
            AppLog.e(TAG, "cleanOrphanIcons failed", t)
            Result.failure(t)
        }
    }

    override suspend fun estimatePrune(): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val response = session.remote.call(retryOnDeath = true) {
                session.service().pruneEstimate(EMPTY_REQUEST)
            }.parseJson<EstimateResponse>()
            if (response.ok) {
                Result.success(response.bytes)
            } else {
                Result.failure(
                    session.remote.error(
                        failureOf(response.error, response.reason, "估算可回收空间失败")
                    )
                )
            }
        } catch (t: Throwable) {
            AppLog.e(TAG, "prune estimate failed", t)
            Result.failure(t)
        }
    }

    /**
     * 把本程序自己的安装包复制到仓库根目录，方便从仓库里直接装回来。
     *
     * 只在「备份」类操作里调：普通备份和覆盖备份都算，恢复 / 删除不调。
     * 受 [backupPrefs.backupSelf] 控制；仓库路径为空时直接返回。
     */
    override suspend fun exportSelfApk() = withContext(Dispatchers.IO) {
        if (!backupPrefs.backupSelf) return@withContext
        // 远程仓库没有「根目录」可以 cp，跳过（不影响备份主流程）
        if (repoPrefs.isRemote) return@withContext
        val dir = session.repoPath
        if (dir.isBlank()) return@withContext

        runCatching {
            val source = session.application.applicationInfo.sourceDir
            val dest = "$dir/app.apk"
            val result = Shell.cmd("cp -f \"$source\" \"$dest\"").exec()
            if (!result.isSuccess) {
                AppLog.w(TAG, "copySelfApk 失败（不影响备份主流程）：${result.err.joinToString()}")
            }
        }.onFailure { AppLog.w(TAG, "copySelfApk 失败（不影响备份主流程）", it) }
    }
}
