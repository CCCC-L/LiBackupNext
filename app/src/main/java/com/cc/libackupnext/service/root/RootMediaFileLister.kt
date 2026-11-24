package com.cc.libackupnext.service.root

import com.cc.libackupnext.service.media.MediaFileLister
import com.cc.libackupnext.util.AppLog
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 用 root 直接列物理目录，给媒体索引重建用。
 *
 * 这里只借 root 拿路径，扫描仍然由主进程发起 —— MediaProvider 收到 FUSE 路径后
 * 会自己去 `/data/media` 读文件，不需要主进程在那个路径上看得见。
 */
internal class RootMediaFileLister : MediaFileLister {

    override suspend fun list(physicalDir: String): List<String>? = withContext(Dispatchers.IO) {
        val quoted = "'" + physicalDir.replace("'", "'\\''") + "'"
        val startedAt = System.currentTimeMillis()

        val result = runCatching {
            // 2>/dev/null：目录里个别子目录读不进去时不要污染 stdout
            Shell.cmd("find $quoted -type f 2>/dev/null").exec()
        }.onFailure { AppLog.w(TAG, "find 失败：$physicalDir", it) }
            .getOrNull()
            ?: return@withContext null

        if (!result.isSuccess) {
            AppLog.w(TAG, "find 退出码 ${result.code}：$physicalDir")
            return@withContext null
        }

        // 逐目录调用，只留 logcat
        AppLog.d(
            TAG,
            "root 列目录 $physicalDir：${result.out.size} 个文件，" +
                    "耗时 ${System.currentTimeMillis() - startedAt}ms"
        )
        result.out.filter { it.isNotBlank() }
    }

    private companion object {
        const val TAG = "RootMediaFileLister"
    }
}
