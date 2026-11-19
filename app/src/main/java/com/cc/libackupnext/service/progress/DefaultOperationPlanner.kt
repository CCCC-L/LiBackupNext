package com.cc.libackupnext.service.progress

import com.cc.libackupnext.data.BackupEntry
import com.cc.libackupnext.data.BackupGroup
import com.cc.libackupnext.data.BackupStep
import com.cc.libackupnext.data.BackupStepStatus
import com.cc.libackupnext.data.SnapshotKind
import com.cc.libackupnext.data.kind
import com.cc.libackupnext.data.BackupProgress

/**
 * [OperationPlanner] 的默认实现：全部是纯函数，没有 IO、没有 Android 依赖。
 */
object DefaultOperationPlanner : OperationPlanner {

    /**
     * 进度弹窗的步骤顺序：目录 → apk → obb → 权限 → user → user_de → data → 系统数据。
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
        SnapshotKind.CALLS,
        // 短信先于彩信：恢复时彩信的会话要建在短信已经建好的 thread 上
        SnapshotKind.SMS,
        SnapshotKind.MMS
    )

    override fun backupSteps(groups: Set<BackupGroup>): List<BackupStep> =
        STEP_ORDER.filter { kind -> groups.any { it.covers(kind) } }
            .map { BackupStep(it, BackupStepStatus.WAITING) }

    override fun restoreSteps(entry: BackupEntry, groups: Set<BackupGroup>): List<BackupStep> {
        val kinds = entry.selectedSnapshots(groups).mapNotNull { it.kind() }.toSet()
        return STEP_ORDER.filter { it in kinds }.map { BackupStep(it, BackupStepStatus.WAITING) }
    }

    override fun applyProgress(
        steps: List<BackupStep>,
        progress: BackupProgress
    ): List<BackupStep> {
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

    override fun markFailed(steps: List<BackupStep>): List<BackupStep> = steps.map {
        if (it.status == BackupStepStatus.WAITING || it.status == BackupStepStatus.RUNNING) {
            it.copy(status = BackupStepStatus.FAILED)
        } else {
            it
        }
    }

    override fun canOverwrite(installed: Boolean): Boolean = installed

    override fun restoreBlockedReason(
        entry: BackupEntry,
        groups: Set<BackupGroup>,
        installed: Boolean
    ): String? {
        val selected = entry.selectedSnapshots(groups)
        if (selected.isEmpty()) return "请先勾选要恢复的内容"

        // 目录和系统数据（Wi-Fi / 联系人 / 通话记录）都不依赖应用装没装
        if (selected.all {
                it.kind() == SnapshotKind.DIR || it.kind() in SnapshotKind.SYSTEM_KINDS
            }
        ) {
            return null
        }

        val hasApk = selected.any { it.kind() == SnapshotKind.APK }
        val needsApp = selected.any { it.kind() != SnapshotKind.APK }
        return if (needsApp && !hasApk && !installed) "应用未安装，无法恢复数据" else null
    }

    private fun statusOf(state: String, fallback: BackupStepStatus): BackupStepStatus =
        when (state) {
            "running" -> BackupStepStatus.RUNNING
            "done" -> BackupStepStatus.DONE
            "skipped" -> BackupStepStatus.SKIPPED
            else -> fallback
        }
}
