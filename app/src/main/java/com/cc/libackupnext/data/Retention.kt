package com.cc.libackupnext.data

/**
 * 保留策略：算「哪些备份该删」，不查仓库、不删数据、不打日志。
 *
 * 判据不是「最旧」，而是「被整行替代」：
 * 一行备份，只有当**更新的某一次备份**把它覆盖的内容全部包含时，才可以删。
 *
 * 「几次碎片备份合起来覆盖」不算替代 —— 否则唯一一次完整备份会被几次碎片吃掉
 * （全量 → user → apk+data 就是这个坑），恢复时只能跨行拼。
 *
 * 由此得到三个不变量：
 * 1. 每种内容至少留最新的一份 —— 最新那份所在的行没有更新的副本，永远不会被删；
 * 2. 最近一次完整备份不会被碎片备份删掉，只会在下一次完整备份到来时让位；
 * 3. 行数上限是**软的** —— 候选全被保护时允许超出（上限 1 时最多「1 次完整 + 每种内容最新一份」）。
 *
 * 新增内容类型只需要在 [BackupGroup] 加枚举值：
 * 保护判据读的是 [BackupGroup.covers]，不是写死的类型列表。
 */
object Retention {

    /**
     * 一次逻辑备份 = 详情页一行：同一个时间戳下的全部快照。
     *
     * 只读视图，构造时算好 [covered] / [ids]，调用方不需要知道 label / tag 怎么解析。
     */
    data class Row(
        /** 分组键：tag 里的时间戳，解析不出来退回快照时间（与 Rust 侧 backup_stamp 一致）。 */
        val stamp: Long,
        val snapshots: List<BackupSnapshot>
    ) {
        /** 删这一行时要删掉的快照 id。 */
        val ids: List<String> = snapshots.map { it.id }

        /** 这一行覆盖了哪几种勾选单位（apk 含 obb / 权限，user 含 user_de）。 */
        val covered: Set<BackupGroup> = BackupGroup.entries.filterTo(mutableSetOf()) { group ->
            snapshots.any { group.covers(it.kind()) }
        }

        /** 有本版本认不出的快照类型：整行保护，绝不删。 */
        val hasUnknownKind: Boolean = snapshots.any { it.kind() == null }
    }

    /**
     * 把某个目标的全部快照分组成「一次备份一行」，按时间从旧到新。
     *
     * 图标快照不属于任何一次备份（仓库里只留一份），排除在分组外 ——
     * 这样清理永远不会误删它，[plan] 也就不需要认识图标。
     */
    fun rows(snapshots: List<BackupSnapshot>): List<Row> = snapshots
        .filter { it.kind() != SnapshotKind.ICON }
        .groupBy { stampOf(it) }
        .map { (stamp, group) -> Row(stamp, group) }
        .sortedBy { it.stamp }

    /**
     * 该删哪几行。从最旧的开始看，直到行数回到 limit 以内。
     *
     * @param limit 每种内容最多保留几份；`<= 0` 表示不限
     * @param justWritten 刚写进去那次备份的时间戳。用它精确排除刚写入的行 ——
     *   不能靠「它一定是最新的」，系统时间回拨时会把刚备份的数据当成旧数据删掉
     */
    fun plan(rows: List<Row>, limit: Int, justWritten: Long): List<Row> {
        if (limit <= 0 || rows.size <= limit) return emptyList()

        val oldestFirst = rows.sortedBy { it.stamp }
        val alive = oldestFirst.toMutableList()
        val victims = mutableListOf<Row>()

        for (row in oldestFirst) {
            if (alive.size <= limit) break
            // 刚写入的那一行、以及带未知类型的那一行，无条件保留
            if (row.stamp == justWritten || row.hasUnknownKind) continue

            // 只有「被更新的某一次备份整行覆盖」才删：
            // 碎片拼起来不算替代 —— 任何一种内容在这个更新备份里缺席，都说明
            // 删掉这一行会让恢复点从「一次拿全」退化成「跨行拼」
            val superseded = row.covered.isNotEmpty() && alive.any { newer ->
                newer.stamp > row.stamp && row.covered.all { it in newer.covered }
            }
            if (!superseded) continue

            alive.remove(row)
            victims += row
        }
        return victims
    }

    /** 分组键：先取 tag 里的时间戳，取不到用快照自己的时间。 */
    private fun stampOf(snapshot: BackupSnapshot): Long =
        SnapshotMeta.timestamp(snapshot.tags) ?: snapshot.time
}
