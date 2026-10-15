package com.cc.libackupnext.data

/** 快照类型：label 冒号后的部分（apk / obb / user / user_de / data / icon）。 */
fun BackupSnapshot.kind(): String = SnapshotMeta.kind(label)

/**
 * 备份页里的一行：同一次备份产生的多个快照（apk / obb / user / user_de / data）。
 *
 * 纯数据模型，UI 直接渲染；分组、勾选换算等逻辑在
 * [com.cc.libackupnext.service.RusticService] 里。
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

    /** 管理弹窗里真正可勾选的类型（user 包含 user_de）。 */
    val availableKinds: List<String> get() = KINDS.filter { snapshotsOf(it).isNotEmpty() }

    /** 某个类型对应的快照；`apk` 带上 `obb`，`user` 带上 `user_de`。 */
    fun snapshotsOf(kind: String): List<BackupSnapshot> = when (kind) {
        "apk" -> snapshots.filter { it.kind() == "apk" || it.kind() == "obb" }
        "user" -> snapshots.filter { it.kind() == "user" || it.kind() == "user_de" }
        else -> snapshots.filter { it.kind() == kind }
    }

    /** 勾选的类型对应的快照。 */
    fun selectedSnapshots(kinds: Set<String>): List<BackupSnapshot> =
        snapshots.filter { snapshot -> kinds.any { it.covers(snapshot.kind()) } }

    companion object {
        /** 管理弹窗里的类型顺序。 */
        val KINDS = listOf("apk", "user", "data")

        /** apk 勾选同时覆盖 obb；user 勾选同时覆盖 user_de 和权限快照。 */
        private fun String.covers(kind: String): Boolean =
            this == kind ||
                    (this == "apk" && kind == "obb") ||
                    (this == "user" && (kind == "user_de" || kind == "permissions"))
    }
}

/** 备份 / 恢复进度里的一步。 */
enum class BackupStepStatus { WAITING, RUNNING, DONE, SKIPPED, FAILED }

data class BackupStep(
    val key: String,
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
