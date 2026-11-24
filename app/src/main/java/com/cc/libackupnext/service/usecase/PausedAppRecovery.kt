package com.cc.libackupnext.service.usecase

import com.cc.libackupnext.data.prefs.BackupPrefs
import com.cc.libackupnext.service.root.AppManagerClient

/**
 * 备份被打断之后的收尾：把还停在 SIGSTOP 的应用放回去。
 *
 * 备份会 `pauseApp`（SIGSTOP）目标应用，正常结束时由备份流程自己 `resume`。
 * 但如果进程被强杀 / native abort，那些应用会一直卡在暂停状态 —— 用户看到的是
 * 「点了图标没反应」。所以启动时按 [BackupPrefs] 里记录的清单补一遍 SIGCONT。
 *
 * 放在 usecase 而不是 root 通信层：它读 prefs、解析 `pkg|userId`，是业务，
 * 和 binder 没关系。
 */
object PausedAppRecovery {

    suspend fun resumeAll(prefs: BackupPrefs) {
        val keys = prefs.pausedApps()
        if (keys.isEmpty()) return

        keys.forEach { key ->
            val parts = key.split('|')
            val packageName = parts.getOrNull(0).orEmpty()
            val userId = parts.getOrNull(1)?.toIntOrNull() ?: return@forEach
            if (packageName.isNotEmpty()) {
                // 应用可能已经卸载 / 进程早就没了，失败一律忽略
                runCatching { AppManagerClient.resumeApp(packageName, userId) }
            }
        }
        prefs.clearPausedApps()
    }
}
