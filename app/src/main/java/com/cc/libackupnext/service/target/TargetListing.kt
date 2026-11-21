package com.cc.libackupnext.service.target

import com.cc.libackupnext.data.AppBackupSummary
import com.cc.libackupnext.data.AppInfo
import com.cc.libackupnext.data.BackupTarget
import com.cc.libackupnext.data.MediaPath
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
     * @param extraDirs 当前备份目标里的目录（配置层：user 段已归一）
     * @param backedUpDirs 仓库里有备份的目录（已按当前用户筛过）
     * @param currentUserId 顶栏选中的用户：目录要展开到他的空间
     * @param exists 目录是否还在当前用户的空间里
     */
    fun rows(
        target: BackupTarget,
        apps: List<AppInfo>,
        create: Boolean,
        hasBackup: (String) -> Boolean,
        extraDirs: Set<String> = emptySet(),
        backedUpDirs: Set<String> = emptySet(),
        currentUserId: Int = MediaPath.PRIMARY_USER_ID,
        exists: (String) -> Boolean = { File(it).isDirectory },
    ): List<AppInfo> {
        val all = when (target) {
            // 新建页只列装着的应用；管理页连「只在备份里」的一起列（备份里有就有得管）
            BackupTarget.APP -> apps.filterNot { create && it.onlyInBackup }
            // 新建页只看备份目标，管理页再加上「仓库里有备份的目录」
            BackupTarget.DIR -> dirRows(
                extraDirs = extraDirs,
                backedUpDirs = if (create) emptySet() else backedUpDirs,
                currentUserId = currentUserId,
                exists = exists
            )
            // 固定目标永远只有一行，和系统里有没有数据无关
            else -> listOf(target.fixedAppInfo())
        }
        return if (create) all else all.filter { hasBackup(it.packageName) }
    }

    /**
     * 文件夹的行：**备份目标 ∪ 仓库里有备份的目录**，再去掉「不存在、也没备份」的。
     *
     * 备份目标存的是「用户无关」的路径（[MediaPath.normalize] 过），这里按
     * [currentUserId] 展开 —— 同一个「DCIM」配置，在用户 10 视角下就是
     * `/storage/emulated/10/DCIM`，备份也落到 `/data/media/10/DCIM`。
     *
     * - 在当前用户空间里存在 → 正常显示；
     * - 不存在但有备份 → 仍然显示（划线），那是唯一能恢复它的入口；
     * - 不存在也没有备份 → 不显示：既不能备份也没法恢复，留着只是噪音。
     */
    fun dirRows(
        extraDirs: Set<String>,
        backedUpDirs: Set<String> = emptySet(),
        currentUserId: Int = MediaPath.PRIMARY_USER_ID,
        exists: (String) -> Boolean = { File(it).isDirectory },
    ): List<AppInfo> {
        val targets = extraDirs.mapTo(LinkedHashSet()) { MediaPath.forUser(it, currentUserId) }
        // 备份身份是「用户无关」的路径，和配置同一套 key：备份过的文件夹在哪个用户视角下
        // 都是同一行，只是指向各自空间里的那份
        val backedUp = backedUpDirs.mapTo(LinkedHashSet()) { MediaPath.forUser(it, currentUserId) }
        return (targets + backedUp)
            .filter { exists(it) || it in backedUp }
            .sorted()
            .map {
                dirAppInfo(
                    path = it,
                    userId = MediaPath.userOf(it) ?: MediaPath.PRIMARY_USER_ID,
                    onlyInBackup = it !in targets
                )
            }
    }

    /**
     * 备份概况里哪些 key 是目录（归一后的路径）。
     *
     * 目录目标的「包名」就是绝对路径，应用包名不会以 `/` 开头 —— 这个约定只在这里
     * 出现一次，界面不用知道。归一是因为**目录的备份列表跨用户共享**：在用户 0 里
     * 备份过的 test，切到用户 10 也要能看到那一行（灰的，因为 10 的空间里没有）。
     * SD 卡本来就不归属用户，归一不动它。
     */
    fun backedUpDirs(summaries: Map<String, AppBackupSummary>): Set<String> =
        summaries.keys.filterTo(LinkedHashSet()) { path ->
            path.startsWith('/')
        }.mapTo(LinkedHashSet()) { MediaPath.normalize(it) }
}
