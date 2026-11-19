package com.cc.libackupnext.service.usecase

import com.cc.libackupnext.data.BackupEntry
import com.cc.libackupnext.data.BackupGroup
import com.cc.libackupnext.data.BackupTarget
import com.cc.libackupnext.data.AppInfo
import com.cc.libackupnext.service.gateway.DeviceGateway
import com.cc.libackupnext.data.BackupProgress
import com.cc.libackupnext.service.gateway.RepoWriter
import com.cc.libackupnext.service.progress.OperationPlanner

/**
 * 恢复的业务编排：判断能不能恢复、停掉应用、再交给仓库执行。
 *
 * 和 [RepoWriter.restoreSnapshots] 的分工：
 * - `restoreSnapshots` 只负责把「恢复这几条快照」的命令送下去；
 * - 这里负责**前置判断和运行环境**：勾选是否有效、应用没装时能不能恢复、
 *   恢复前要不要先杀掉应用进程。
 *
 * 前置判断本身委托给 [OperationPlanner.restoreBlockedReason]（纯逻辑，可单测），
 * 这里只负责在正确时机调用它，并把结果翻译成 `Result.failure`。
 */
interface RestoreUseCase {

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
    ): Result<Unit>
}

/**
 * [RestoreUseCase] 的默认实现。
 *
 * 依赖 [RepoWriter]（执行）和 [OperationPlanner]（判断），
 * 两者都是接口，所以这层可以用假的 writer + 真的 planner 直接测。
 */
internal class DefaultRestoreUseCase(
    private val writer: RepoWriter,
    private val planner: OperationPlanner,
    private val device: DeviceGateway,
) : RestoreUseCase {

    override suspend fun restore(
        app: AppInfo,
        entry: BackupEntry,
        groups: Set<BackupGroup>,
        installed: Boolean,
        onProgress: (BackupProgress) -> Unit
    ): Result<Unit> {
        val selected = entry.selectedSnapshots(groups)
        planner.restoreBlockedReason(entry, groups, installed)?.let {
            return Result.failure(IllegalStateException(it))
        }

        // 恢复前先停掉应用：一边写一边被覆盖会拿到半新半旧的数据
        // （目录 / 系统数据不是应用，没有进程可停）
        if (BackupTarget.of(app) == BackupTarget.APP) {
            device.stop(app.packageName, app.userId)
        }

        return writer.restoreSnapshots(app, selected, onProgress)
    }
}
