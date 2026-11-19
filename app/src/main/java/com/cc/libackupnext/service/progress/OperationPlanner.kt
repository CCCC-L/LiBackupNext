package com.cc.libackupnext.service.progress

import com.cc.libackupnext.data.BackupEntry
import com.cc.libackupnext.data.BackupGroup
import com.cc.libackupnext.data.BackupStep
import com.cc.libackupnext.data.BackupProgress

/**
 * 操作进度的**纯逻辑**：把「要显示哪几步」和「一条进度事件落到哪一步」算出来。
 */
interface OperationPlanner {

    /** 备份要显示的步骤（顺序固定：目录 → apk → obb → 权限 → user → user_de → data → 系统数据）。 */
    fun backupSteps(groups: Set<BackupGroup>): List<BackupStep>

    /** 恢复要显示的步骤：只列出真正勾选到的快照。 */
    fun restoreSteps(entry: BackupEntry, groups: Set<BackupGroup>): List<BackupStep>

    /** 把一条进度事件合并进步骤列表。 */
    fun applyProgress(steps: List<BackupStep>, progress: BackupProgress): List<BackupStep>

    /** 失败时把还没跑完的步骤标成失败。 */
    fun markFailed(steps: List<BackupStep>): List<BackupStep>

    /** 覆盖备份按钮是否展示：应用没装就不允许覆盖。 */
    fun canOverwrite(installed: Boolean): Boolean

    /**
     * 恢复前的检查：返回要提示给用户的原因，null 表示可以恢复。
     *
     * 判据只看勾选的快照类型与「应用是否已安装」，不访问仓库 ——
     * 所以能在弹窗打开前就给出结论，不用先发一次 IPC。
     */
    fun restoreBlockedReason(
        entry: BackupEntry,
        groups: Set<BackupGroup>,
        installed: Boolean
    ): String?
}
