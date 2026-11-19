package com.cc.libackupnext.service.root.sysdata

import android.content.Context
import android.util.Log
import com.cc.libackupnext.data.SnapshotKind
import java.io.File

/**
 * 把 [SystemDataExporter] 导出的内容写回系统。
 *
 * 全部走 provider（[ProviderAccess]）/ WifiManager，不碰数据库文件。恢复是**合并**不是覆盖：
 * 每条记录先算一个指纹，已经存在的跳过，重复恢复同一份备份不会翻倍。
 *
 * 具体每种数据怎么做由 [SystemDataHandlers] 里的实现决定，这里只负责分派。
 * 加一种新类型不需要改这个文件。
 */
internal object SystemDataRestorer {

    private const val TAG = "SystemDataRestorer"

    /**
     * 从快照目录恢复（彩信要从里面读附件文件）。
     */
    fun restoreFrom(context: Context, kind: SnapshotKind, dir: File): RestoreResult = try {
        SystemDataHandlers[kind]?.restoreFrom(context, dir) ?: RestoreResult(0, 0)
    } catch (t: Throwable) {
        Log.e(TAG, "恢复 ${kind.key} 失败", t)
        throw t
    }
}
