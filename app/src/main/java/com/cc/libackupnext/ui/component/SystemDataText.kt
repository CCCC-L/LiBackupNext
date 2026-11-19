package com.cc.libackupnext.ui.component

import com.cc.libackupnext.data.BackupGroup
import com.cc.libackupnext.data.BackupTarget
import com.cc.libackupnext.data.SnapshotKind
import com.cc.libackupnext.data.target.TargetSpecs

/**
 * 固定目标的各分项条数。
 *
 * Wi-Fi / 联系人 / 通话记录只有一项；短信有短信和彩信两项，要分开列出来
 */
fun BackupTarget.systemCounts(counts: Map<SnapshotKind, Int>): List<Pair<BackupGroup, Int>> =
    kinds.mapNotNull { group ->
        group.kinds.firstOrNull()?.let { kind -> counts[kind] }?.let { group to it }
    }

/**
 * 「系统信息」页的副标题：已保存 12 个 / 共 123 个 / 共 86 条 / 短信 22 · 彩信 2。
 */
fun BackupTarget.countText(counts: Map<SnapshotKind, Int>): String =
    TargetSpecs.of(this).countText(counts)
