package com.cc.libackupnext.data

/** 快照类型：label 冒号后的部分。认不出来的类型是 null。 */
fun BackupSnapshot.kind(): SnapshotKind? = SnapshotMeta.kind(label)

/**
 * 备份页里的一行：同一次备份产生的多个快照（apk / obb / user / user_de / data）。
 *
 * 纯数据模型，UI 直接渲染；备份 / 恢复的编排在
 * [com.cc.libackupnext.service.RusticClient] 里。
 */
data class BackupEntry(
    val title: String,
    val time: Long,
    val description: String?,
    val version: String?,
    val userName: String?,
    val snapshots: List<BackupSnapshot>
) {

    val ids: List<String> get() = snapshots.map { it.id }

    /**
     * 这条备份里真正可勾选的单位。
     *
     * 应用条目里没有 dir 快照、目录条目里没有 apk/user/data，靠「有没有快照」自然过滤掉。
     */
    val availableKinds: List<BackupGroup> by lazy(LazyThreadSafetyMode.NONE) {
        BackupGroup.entries.filter { snapshotsOf(it).isNotEmpty() }
    }

    /** 某个勾选单位对应的快照；`apk` 带上 `obb`，`user` 带上 `user_de` 和权限。 */
    fun snapshotsOf(group: BackupGroup): List<BackupSnapshot> =
        snapshots.filter { group.covers(it.kind()) }

    /** 勾选的单位对应的快照。 */
    fun selectedSnapshots(groups: Set<BackupGroup>): List<BackupSnapshot> =
        snapshots.filter { snapshot -> groups.any { it.covers(snapshot.kind()) } }
}

/** 备份 / 恢复进度里的一步。 */
enum class BackupStepStatus { WAITING, RUNNING, DONE, SKIPPED, FAILED }

data class BackupStep(
    val kind: SnapshotKind,
    val status: BackupStepStatus,
    val files: Long = 0,
    val reason: String = ""
)

/** 某个包在仓库里的备份概况：备份次数 + 最近一次备份。 */
data class AppBackupSummary(
    val packageName: String,
    val count: Int,
    val latest: BackupEntry,
    /** 仓库里已经有这个应用的图标快照 */
    val hasIcon: Boolean = false
)
