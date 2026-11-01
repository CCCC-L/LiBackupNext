package com.cc.libackupnext.data

import com.cc.libackupnext.entity.AppInfo
import com.cc.libackupnext.entity.DefaultBackupDirs
import com.cc.libackupnext.entity.dirAppInfo

/**
 * 批量页 / 列表页备份的「目标类型」。
 *
 * 每种类型自己决定：列表怎么来、统计里叫什么单位、快照有哪些内容类型（列头那几列）。
 * 以后要加「数据库备份」「微信记录」这类，只要在这里加一个枚举值 + 补一个 [targets] 分支。
 *
 * @param unit 统计文字里的单位，例如「个应用」
 * @param kinds 快照的内容类型，也是列表顶部的列
 */
enum class BackupTarget(val unit: String, val kinds: List<String>) {

    /** 已安装的应用：apk / user / data 三列。 */
    APP("个应用", listOf("apk", "user", "data")),

    /** 目录备份：只有一列 dir，快照 label 是 `<绝对路径>:dir`。 */
    DIR("个文件夹", listOf("dir"));

    /** 是不是「应用」这种目标（决定详情页要不要走安装状态、备份选项长什么样）。 */
    val isApp: Boolean get() = this == APP

    /** 详情页顶栏标题。 */
    val detailTitle: String get() = when (this) {
        APP -> "应用信息"
        DIR -> "文件信息"
    }

    /** 详情页点「备份」时默认勾上的内容。 */
    val backupKinds: Set<String> get() = kinds.toSet()

    /**
     * 这一页要列的目标。
     *
     * @param apps 应用列表（[APP] 用）
     * @param create true = 新建备份页（全部目标），false = 管理页（只留有备份的）
     * @param hasBackup 这个包名 / 路径在仓库里有没有备份
     */
    fun targets(
        apps: List<AppInfo>,
        create: Boolean,
        hasBackup: (String) -> Boolean
    ): List<AppInfo> {
        val all = when (this) {
            APP -> apps.filterNot { create && it.onlyInBackup }
            DIR -> backupDirs(AppPrefs.extraDirs).map { dirAppInfo(it) }
        }
        return if (create) all else all.filter { hasBackup(it.packageName) }
    }
}

/**
 * 文件页的目录列表：默认那几个按固定顺序排前面，用户自己加的按字母序排后面。
 *
 * [extra] 已经包含默认目录了（`AppPrefs.extraDirs` 的默认值就是它们），所以这里不再补。
 */
fun backupDirs(extra: Set<String>): List<String> =
    DefaultBackupDirs.filter { it in extra } + extra.filterNot { it in DefaultBackupDirs }.sorted()
