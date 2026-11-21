package com.cc.libackupnext.service.root

import android.content.Context
import com.cc.libackupnext.data.SnapshotKind
import com.cc.libackupnext.data.json.BackupItemDto
import com.cc.libackupnext.data.json.RusticBackupRequest
import com.cc.libackupnext.service.root.sysdata.SystemDataExporter
import java.io.File

/**
 * 备份的「系统数据」准备与清理：Wi-Fi / 联系人 / 通话记录 / 短彩信不是文件，
 * 备份前要先由 root 侧导出成 JSON（彩信还带附件文件），备份完再删掉临时目录。
 *
 * 从 `RusticServer.Impl` 里抽出来：它是备份流程里唯一和「文件快照」无关的一段，
 * 逻辑自洽（导出 → 记路径 → 清理），和 rustic 命令没有关系。
 */
internal class RusticBackupSupport(private val context: Context) {

    /**
     * 系统数据（Wi-Fi / 联系人 / 通话记录 / 短信）的快照不是文件：
     * 备份前先导出到一个临时目录，整个目录进快照（`as_path = "/"`，快照根就是目录内容）。
     *
     * 用目录而不是单文件是为了彩信 —— 附件是独立文件、流复制进去，多大都不占内存。
     * 一个目标可能有多个 item（短信目标下有 sms / mms 两个），各自一个目录。
     * 导出失败时把 item 标成跳过 —— Rust 侧看到空 sources 会自己跳。
     */
    fun prepareSystemItems(request: RusticBackupRequest): RusticBackupRequest {
        val items = request.items.map { item: BackupItemDto ->
            if (item.system != true) return@map item
            val kind = SnapshotKind.of(item.stage) ?: return@map item

            val dir = systemDataDir(kind)
            if (!SystemDataExporter.exportTo(context, kind, dir)) {
                dir.deleteRecursively()
                return@map item.copy(skipReason = "导出失败")
            }
            item.copy(
                sources = listOf(dir.absolutePath),
                asPath = "/",
                // 源换了就得按新路径重新判定，别让复制前的值漏过来
                ignoreInode = ignoreInodeFor(listOf(dir.absolutePath))
            )
        }
        return request.copy(items = items)
    }

    /** 导出用的临时目录；每次先清空，别让上一轮的残留混进快照。 */
    fun systemDataDir(kind: SnapshotKind): File =
        File(context.cacheDir, "sysdata_${kind.key}").apply {
            deleteRecursively()
            mkdirs()
        }

    /** 备份结束后删掉导出用的临时目录。 */
    fun cleanupSystemItems(request: RusticBackupRequest) {
        request.items.forEach { item ->
            if (item.system != true) return@forEach
            val kind = SnapshotKind.of(item.stage) ?: return@forEach
            runCatching { File(context.cacheDir, "sysdata_${kind.key}").deleteRecursively() }
        }
    }
}
