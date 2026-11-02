package com.cc.libackupnext.ui.util

import com.cc.libackupnext.data.BackupTarget
import com.cc.libackupnext.data.SnapshotKind

/** 固定目标对应的系统数据条数；不是固定目标、或者还没读到就是 null。 */
fun BackupTarget.systemCount(counts: Map<SnapshotKind, Int>): Int? =
    kinds.firstOrNull()?.kinds?.firstOrNull()?.let { counts[it] }

/** 「其他」页的副标题：已保存 12 个 / 共 123 个 / 共 86 条。 */
fun BackupTarget.countText(count: Int?): String = when (this) {
    BackupTarget.WIFI -> if (count == null) "—" else "已保存 $count 个"
    BackupTarget.CONTACTS -> if (count == null) "—" else "共 $count 个"
    BackupTarget.CALLS -> if (count == null) "—" else "共 $count 条"
    else -> ""
}
