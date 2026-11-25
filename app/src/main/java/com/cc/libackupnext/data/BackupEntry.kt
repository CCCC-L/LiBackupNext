package com.cc.libackupnext.data

import kotlinx.serialization.Serializable

/** 快照类型：label 冒号后的部分。认不出来的类型是 null。 */
fun BackupSnapshot.kind(): SnapshotKind? = SnapshotMeta.kind(label)

/**
 * 备份页里的一行：同一次备份产生的多个快照（apk / obb / user / user_de / data）。
 *
 * 纯数据模型，UI 直接渲染；备份 / 恢复的编排在
 * [com.cc.libackupnext.service.usecase.BackupUseCase] 与
 * [com.cc.libackupnext.service.usecase.RestoreUseCase] 里。
 */
@Serializable
data class BackupEntry(
    val title: String = "",
    val time: Long = 0,
    val description: String? = null,
    val version: String? = null,
    val userName: String? = null,
    val snapshots: List<BackupSnapshot> = emptyList()
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

    /** 某个勾选单位对应的快照；`apk` 带上 `obb` 和权限，`user` 带上 `user_de`。 */
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
    /** 这一步有多少个源文件读不出来（0 表示全读到了）。 */
    val errors: Long = 0,
    val reason: String = "",
    /** 这一步的字节总量；0 表示 rustic 还没扫出来，进度条只能用不确定态。 */
    val total: Long = 0,
    /** 这一步已处理的字节数。 */
    val current: Long = 0,
    /** 已过网字节（加密后的实际传输量），本地仓库恒为 0。 */
    val transferred: Long = 0,
    /** 传输速度，字节/秒；0 表示当前没有在传。 */
    val transferSpeed: Long = 0
)

/** 某个包在仓库里的备份概况：备份次数 + 最近一次备份。 */
data class AppBackupSummary(
    val packageName: String,
    val count: Int,
    val latest: BackupEntry,
    /** 仓库里已经有这个应用的图标快照 */
    val hasIcon: Boolean = false
)
