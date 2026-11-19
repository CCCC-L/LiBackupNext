package com.cc.libackupnext.service.usecase

import com.cc.libackupnext.data.prefs.BackupPrefs
import com.cc.libackupnext.data.BackupEntry
import com.cc.libackupnext.data.BackupGroup
import com.cc.libackupnext.data.BackupRequest
import com.cc.libackupnext.data.BackupTarget
import com.cc.libackupnext.data.Retention
import com.cc.libackupnext.data.SnapshotKind
import com.cc.libackupnext.data.SnapshotOverride
import com.cc.libackupnext.data.kind
import com.cc.libackupnext.data.AppInfo
import com.cc.libackupnext.service.gateway.DeviceGateway
import com.cc.libackupnext.data.BackupProgress
import com.cc.libackupnext.service.gateway.RepoReader
import com.cc.libackupnext.service.gateway.RepoWriter
import com.cc.libackupnext.util.AppLog
import kotlinx.coroutines.CancellationException

/**
 * 备份的业务编排：一次「用户点了备份」到底做了什么。
 *
 * 和 [RepoWriter.write] 的分工：
 * - `write` 只负责把一条 rustic 命令送下去；
 * - 这里负责**顺序和条件**：先暂停目标应用、写成功后再清理过期备份、
 *   最后把自身 APK 复制进仓库。
 */
interface BackupUseCase {

    /**
     * 普通备份。
     *
     * @param onProgress 进度回调，由 root 侧的 poller 驱动
     * @param operationId 一次逻辑操作的标识：重试沿用，主动新备份换新值
     * @param batchId 批量备份的批次标识，同一批传同一个值（仓库只打开一次）
     * @param iconAlreadyBackedUp 已知图标已在仓库里就传 true，省一次快照查询
     * @param parentIds 类型 -> 上一次同类快照 id，传给 rustic 当 parent
     * @param knownBackupCount 已知的备份行数，用来判断要不要跑清理
     * @return 清理掉的过期备份行数
     */
    suspend fun backup(
        app: AppInfo,
        groups: Set<BackupGroup>,
        onProgress: (BackupProgress) -> Unit = {},
        operationId: Long = System.currentTimeMillis(),
        batchId: String? = null,
        iconAlreadyBackedUp: Boolean? = null,
        parentIds: Map<SnapshotKind, String>? = null,
        knownBackupCount: Int? = null
    ): Result<Int>

    /**
     * 覆盖备份：沿用旧快照的 label / tag / 备注，新的写成功了再删掉旧的。
     *
     * @param batchId 批量覆盖的批次标识，传了就由调用方在整批结束后统一导出自身安装包
     */
    suspend fun overwrite(
        app: AppInfo,
        entry: BackupEntry,
        groups: Set<BackupGroup>,
        onProgress: (BackupProgress) -> Unit = {},
        batchId: String? = null,
        operationId: Long = System.currentTimeMillis()
    ): Result<Unit>
}

/**
 * [BackupUseCase] 的默认实现。
 *
 * 依赖只有 [RepoReader] / [RepoWriter] 两个接口，以及应用管理（暂停进程）。
 * 清理策略本身是 [Retention] 里的纯函数，这里只负责「查列表、调策略、删快照」。
 */
internal class DefaultBackupUseCase(
    private val reader: RepoReader,
    private val writer: RepoWriter,
    private val backupPrefs: BackupPrefs,
    private val device: DeviceGateway,
) : BackupUseCase {

    private companion object {
        const val TAG = "BackupUseCase"
    }

    override suspend fun backup(
        app: AppInfo,
        groups: Set<BackupGroup>,
        onProgress: (BackupProgress) -> Unit,
        operationId: Long,
        batchId: String?,
        iconAlreadyBackedUp: Boolean?,
        parentIds: Map<SnapshotKind, String>?,
        knownBackupCount: Int?
    ): Result<Int> {
        if (groups.isEmpty()) {
            return Result.failure(IllegalStateException("请先选择要备份的内容"))
        }
        val result = withAppPaused(app) {
            writer.write(
                request = request(app, groups),
                onProgress = onProgress,
                batchId = batchId,
                operationId = operationId,
                iconAlreadyBackedUp = iconAlreadyBackedUp,
                parentIds = parentIds
            )
        }
        result.exceptionOrNull()?.let { return Result.failure(it) }

        // 清理放在暂停结束之后：应用已经恢复运行，没必要为收尾多冻它几秒
        val cleaned = enforceRetention(
            packageName = app.packageName,
            knownCount = knownBackupCount,
            justWritten = operationId
        )
        // 批量备份（batchId 有值）留到整批结束后由调用方统一做一次 —— 目标始终是同一个
        if (batchId == null) writer.exportSelfApk()
        return Result.success(cleaned)
    }

    override suspend fun overwrite(
        app: AppInfo,
        entry: BackupEntry,
        groups: Set<BackupGroup>,
        onProgress: (BackupProgress) -> Unit,
        batchId: String?,
        operationId: Long
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
            writer.write(
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
        if (result.isSuccess && batchId == null) writer.exportSelfApk()
        return result
    }

    /**
     * 备份成功后清理过期备份，返回删掉的行数。
     *
     * 只删「已经被更新的备份完全替代」的行 —— 判据在 [Retention]，这里只负责
     * 查列表、调策略、删快照（策略与执行分开，策略才能单独测）。
     *
     * 清理是收尾，不是备份本身：失败不回滚、也不改备份结果。
     *
     * @param knownCount 调用方已知的行数，没到上限就跳过（省掉一次查仓库的 IPC）
     * @param justWritten 刚写进去那次备份的时间戳，用来精确排除刚写入的行
     */
    private suspend fun enforceRetention(
        packageName: String,
        knownCount: Int?,
        justWritten: Long
    ): Int {
        val limit = backupPrefs.retentionLimit
        if (limit <= 0) return 0
        if (knownCount != null && knownCount + 1 <= limit) return 0

        return try {
            val rows = Retention.rows(reader.listSnapshots(packageName).getOrThrow())
            val victims = Retention.plan(rows, limit, justWritten)
            if (victims.isEmpty()) return 0

            writer.deleteSnapshots(victims.flatMap { it.ids }).getOrThrow()
            AppLog.i(TAG, "自动清理 $packageName：${victims.size} 条旧备份")
            victims.size
        } catch (t: CancellationException) {
            // 取消不是失败，不能被吞掉
            throw t
        } catch (t: Throwable) {
            AppLog.w(TAG, "自动清理旧备份失败：$packageName", t)
            0
        }
    }

    /**
     * 备份期间暂停目标应用：给它所有进程发 SIGSTOP，文件就不会再被改写。
     *
     * 备份结束（成功或失败）立刻 SIGCONT 恢复；同时记一份到 BackupPrefs，
     * 万一进程被杀，下次启动会把残留的暂停恢复掉。
     */
    private suspend fun <T> withAppPaused(app: AppInfo, block: suspend () -> T): T {
        // 系统数据不是应用，没有进程可停
        if (BackupTarget.of(app).isFixed) return block()

        val key = "${app.packageName}|${app.userId}"
        val paused = device.pause(app.packageName, app.userId)
        if (paused) backupPrefs.addPausedApp(key)

        return try {
            block()
        } finally {
            if (paused) {
                device.resume(app.packageName, app.userId)
                backupPrefs.removePausedApp(key)
            }
        }
    }

    /** 把「备份哪些内容」翻译成 rustic 的请求体。 */
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
        backupPermissions = BackupGroup.APK in groups,
        // 固定目标要带上勾了哪几项；应用 / 文件夹由上面几个 bool 决定
        kinds = if (BackupTarget.of(app).isFixed) {
            groups.flatMap { it.kinds }.map { it.key }.distinct()
        } else {
            null
        }
    )
}
