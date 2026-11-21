package com.cc.libackupnext.service.target

import com.cc.libackupnext.data.MediaPath
import com.cc.libackupnext.data.prefs.BackupPrefs
import kotlinx.coroutines.flow.StateFlow

/**
 * 备份目标的增删查（目前只有文件夹可增删）。
 *
 * 为什么要单独一层：界面不该直接改 prefs ——「移除该文件夹」的语义（只退出备份目标、
 * 仓库里的备份保留）和「目录恢复成功后加回目标」都是业务规则，收在这里。
 * 界面只调用 + 展示；恢复编排也只认这个接口，不碰存储细节。
 */
interface BackupTargets {

    /** 当前的目录目标；写入后立即推给订阅者（界面靠它刷新，不再自己缓存）。 */
    val dirs: StateFlow<Set<String>>

    /** 把一个目录设为备份目标；已经在里面就什么都不做。 */
    fun addDir(path: String)

    /** 把目录从备份目标里移除；**仓库里的备份不动**，以后还能恢复。 */
    fun removeDir(path: String)
}

/**
 * [BackupTargets] 的默认实现：全部落在 [BackupPrefs] 上。
 *
 * 这里只做「集合的增删」，不判断「该不该加」—— 那个判断在调用方
 * （恢复编排只知道恢复成功才加）。
 */
internal class PrefsBackupTargets(private val prefs: BackupPrefs) : BackupTargets {

    override val dirs: StateFlow<Set<String>> = prefs.extraDirsFlow

    override fun addDir(path: String) {
        // 存「用户无关」的路径：同一个 DCIM 在哪个用户视角下加进来都是同一条配置
        prefs.extraDirs = prefs.extraDirs + MediaPath.normalize(path)
    }

    override fun removeDir(path: String) {
        prefs.extraDirs = prefs.extraDirs - MediaPath.normalize(path)
    }
}
