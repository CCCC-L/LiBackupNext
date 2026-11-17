package com.cc.libackupnext.ui.util

import com.cc.libackupnext.data.BackupGroup
import com.cc.libackupnext.data.BackupTarget
import com.cc.libackupnext.data.SnapshotKind

/**
 * 固定目标的各分项条数。
 *
 * Wi-Fi / 联系人 / 通话记录只有一项；短信有短信和彩信两项，要分开列出来 ——
 * 既然让用户自己勾，就得让人看见每类有多少。
 */
fun BackupTarget.systemCounts(counts: Map<SnapshotKind, Int>): List<Pair<BackupGroup, Int>> =
    kinds.mapNotNull { group ->
        group.kinds.firstOrNull()?.let { kind -> counts[kind] }?.let { group to it }
    }

/**
 * 「其他」页的副标题：已保存 12 个 / 共 123 个 / 共 86 条 / 短信 22 · 彩信 2。
 */
fun BackupTarget.countText(counts: Map<SnapshotKind, Int>): String = when (this) {
    BackupTarget.WIFI -> counts[SnapshotKind.WIFI]?.let { "已保存 $it 个" } ?: "—"
    BackupTarget.CONTACTS -> counts[SnapshotKind.CONTACTS]?.let { "共 $it 个" } ?: "—"
    BackupTarget.CALLS -> counts[SnapshotKind.CALLS]?.let { "共 $it 条" } ?: "—"
    BackupTarget.SMS -> systemCounts(counts)
        .joinToString(" · ") { (group, count) -> "${group.title} $count" }
        .ifBlank { "—" }

    else -> ""
}
