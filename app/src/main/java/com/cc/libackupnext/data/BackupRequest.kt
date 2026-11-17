package com.cc.libackupnext.data

import kotlinx.serialization.Serializable

/**
 * 一次备份请求。
 *
 * @param version 形如 `1.10.2(230102)`，直接进 tag
 */
data class BackupRequest(
    val packageName: String,
    val appName: String,
    val version: String,
    val userId: Int,
    val backupApk: Boolean,
    val backupObb: Boolean,
    val backupUser: Boolean,
    val backupData: Boolean,
    val backupPermissions: Boolean = false,
    /**
     * 固定目标（Wi-Fi / 联系人 / 通话记录 / 短信）勾了哪几项，值是 [SnapshotKind.key]。
     * 应用 / 文件夹传 null —— 它们看上面那几个 bool。
     */
    val kinds: List<String>? = null
)

/**
 * 仓库里的一个快照。
 *
 * @param tags 逗号分隔的 tag 串
 * @param time 快照时间（毫秒）
 */
@Serializable
data class BackupSnapshot(
    val id: String = "",
    val label: String = "",
    val tags: String = "",
    val time: Long = 0,
    val size: Long = 0,
    val description: String? = null
)

/**
 * 一个包的备份概况（Rust 侧 `overview` 命令的返回）。
 *
 * @param snapshots 最近一次备份包含的快照（带 id，删除 / 覆盖 / 恢复要用）
 * @param hasApk 有没有 apk 备份；没有的包不会出现在「只在备份里」的应用列表
 * @param apkTags 最新一条 apk 快照的 tag —— 应用名 / 版本以它为准，
 *   可能比 [tags] 更早（最近一次备份没勾 apk）
 */
data class RepoOverview(
    val packageName: String,
    val count: Int,
    val time: Long,
    val tags: String,
    val description: String?,
    val snapshots: List<BackupSnapshot>,
    val hasApk: Boolean,
    val apkTags: String?,
    val apkTime: Long,
    /** 仓库里已经有 `包名:icon` 快照，备份时不用再存一次 */
    val hasIcon: Boolean
)

/** 覆盖备份时沿用旧快照的 label / tag / 备注。 */
data class SnapshotOverride(
    val label: String,
    val tags: String,
    val description: String?
)
