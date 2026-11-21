package com.cc.libackupnext.service.target

import com.cc.libackupnext.data.AppBackupSummary
import com.cc.libackupnext.data.AppInfo
import com.cc.libackupnext.data.BackupTarget
import com.cc.libackupnext.data.dirAppInfo
import java.io.File

/**
 * 备份目标列表的组装规则：一页该列哪些行、哪一行要标「只剩备份」。
 *
 * 这是**业务规则**，所以放在 service 层；[BackupTarget] 只留元数据（名字、列、固定 id）。
 *
 * 全是纯函数、没有 IO（磁盘存在性由 [exists] 注入），JVM 单测可以直接跑。
 * 文件页和批量页都从这里取行 —— 规则只有一份，改一处两边一起变。
 */
object TargetListing {

    /**
     * 某个目标这一页要列的行。
     *
     * @param apps 应用列表（[BackupTarget.APP] 用）
     * @param create true = 新建备份页，false = 管理页（只留有备份的）
     * @param hasBackup 这个包名 / 路径在仓库里有没有备份
     * @param extraDirs 当前备份目标里的目录
     * @param backedUpDirs 仓库里有备份的目录
     * @param exists 目录是否还在磁盘上
     */
    fun rows(
        target: BackupTarget,
        apps: List<AppInfo>,
        create: Boolean,
        hasBackup: (String) -> Boolean,
        extraDirs: Set<String> = emptySet(),
        backedUpDirs: Set<String> = emptySet(),
        exists: (String) -> Boolean = { File(it).isDirectory },
    ): List<AppInfo> {
        val all = when (target) {
            // 新建页只列装着的应用；管理页连「只在备份里」的一起列（备份里有就有得管）
            BackupTarget.APP -> apps.filterNot { create && it.onlyInBackup }
            // 新建页只看备份目标，管理页再加上「仓库里有备份的目录」
            BackupTarget.DIR -> dirRows(
                extraDirs = extraDirs,
                backedUpDirs = if (create) emptySet() else backedUpDirs,
                exists = exists
            )
            // 固定目标永远只有一行，和系统里有没有数据无关
            else -> listOf(target.fixedAppInfo())
        }
        return if (create) all else all.filter { hasBackup(it.packageName) }
    }

    /**
     * 文件夹的行：**备份目标 ∪ 仓库里有备份的目录**，再去掉「磁盘上没了、也没备份」的。
     *
     * - 在 [extraDirs] 里 = 当前是备份目标，正常显示；
     * - 只在 [backedUpDirs] 里 = 已移除备份目标，备份还在 → 标 `onlyInBackup`，
     *   行会被 `TargetSpecs` 的 `isMissing` 划线置灰，仍可恢复；
     * - 两边都没有、磁盘上也没了 → 不显示：它既不能备份也没法恢复，留着只是噪音。
     *
     * 注：目录被删了但**还有备份**时仍然显示（划线）—— 那是唯一能恢复它的入口。
     */
    fun dirRows(
        extraDirs: Set<String>,
        backedUpDirs: Set<String> = emptySet(),
        exists: (String) -> Boolean = { File(it).isDirectory },
    ): List<AppInfo> = (extraDirs + backedUpDirs)
        .filter { exists(it) || it in backedUpDirs }
        .sorted()
        .map { dirAppInfo(it, onlyInBackup = it !in extraDirs) }

    /**
     * 备份概况里哪些 key 是目录。
     *
     * 目录目标的「包名」就是绝对路径（`/storage/...`），应用包名不会以 `/` 开头 ——
     * 这个约定只在这里出现一次，界面不用知道。
     */
    fun backedUpDirs(summaries: Map<String, AppBackupSummary>): Set<String> =
        summaries.keys.filterTo(LinkedHashSet()) { it.startsWith('/') }
}
