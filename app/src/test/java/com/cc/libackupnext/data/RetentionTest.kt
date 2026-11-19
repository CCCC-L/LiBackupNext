package com.cc.libackupnext.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 保留策略的纯函数测试：不起 Android、不碰仓库。
 *
 * 覆盖用户提的四条场景 + 上限 1 的「各留一份」 + 孤本保护/未知类型/图标这些边角。
 */
class RetentionTest {

    // ==================== 造数据 ====================

    private fun snap(
        kind: SnapshotKind,
        stamp: Long,
        label: String = "com.test:${kind.key}"
    ) = BackupSnapshot(
        id = "${kind.key}@$stamp",
        label = label,
        tags = "测试|1.0.0|0|$stamp",
        time = stamp
    )

    /** 一次逻辑备份 = 一行；stamp 越大越新。 */
    private fun backup(stamp: Long, vararg kinds: SnapshotKind): List<BackupSnapshot> =
        kinds.map { snap(it, stamp) }

    /** 跑一遍策略，返回被删掉的行的时间戳（按删除顺序）。 */
    private fun deleted(
        limit: Int,
        justWritten: Long,
        vararg backups: List<BackupSnapshot>
    ): List<Long> = Retention
        .plan(Retention.rows(backups.flatMap { it }), limit, justWritten)
        .map { it.stamp }

    // ==================== 需求里的四条场景 ====================

    @Test
    fun `apk 是孤本时跳过它，删后面的 user`() {
        val result = deleted(
            3, 4,
            backup(1, SnapshotKind.APK),
            backup(2, SnapshotKind.USER),
            backup(3, SnapshotKind.USER),
            backup(4, SnapshotKind.USER)
        )
        assertEquals(listOf(2L), result)
    }

    @Test
    fun `apk 还有别的副本时删最旧那行`() {
        val result = deleted(
            3, 4,
            backup(1, SnapshotKind.APK),
            backup(2, SnapshotKind.APK),
            backup(3, SnapshotKind.APK),
            backup(4, SnapshotKind.USER)
        )
        assertEquals(listOf(1L), result)
    }

    @Test
    fun `备份 user 时删掉被替代的那行 user`() {
        val result = deleted(
            3, 4,
            backup(1, SnapshotKind.APK),
            backup(2, SnapshotKind.USER),
            backup(3, SnapshotKind.DATA),
            backup(4, SnapshotKind.USER)
        )
        assertEquals(listOf(2L), result)
    }

    @Test
    fun `备份 data 时删掉被替代的那行 data`() {
        val result = deleted(
            3, 4,
            backup(1, SnapshotKind.APK),
            backup(2, SnapshotKind.USER),
            backup(3, SnapshotKind.DATA),
            backup(4, SnapshotKind.DATA)
        )
        assertEquals(listOf(3L), result)
    }

    @Test
    fun `整行都被替代才删，老行里的 apk 和 data 保住它`() {
        val result = deleted(
            3, 4,
            backup(1, SnapshotKind.APK, SnapshotKind.USER, SnapshotKind.DATA),
            backup(2, SnapshotKind.USER),
            backup(3, SnapshotKind.USER),
            backup(4, SnapshotKind.USER)
        )
        assertEquals(listOf(2L), result)
    }

    // ==================== 上限 1：完整备份 + 每种内容最新一份 ====================

    @Test
    fun `上限 1 时保留完整备份，碎片备份各自留最新一份`() {
        val backups = listOf(
            backup(1, SnapshotKind.APK, SnapshotKind.USER, SnapshotKind.DATA),
            backup(2, SnapshotKind.APK),
            backup(3, SnapshotKind.USER),
            backup(4, SnapshotKind.DATA)
        )
        val rows = Retention.rows(backups.flatten())
        val victims = Retention.plan(rows, limit = 1, justWritten = 4)

        // 没有任何一次更新的备份能整行覆盖第 1 行 → 它是「最近一次完整备份」，留下
        assertTrue(victims.isEmpty())
        assertEquals(4, rows.size)
    }

    @Test
    fun `全量之后又备份了碎片，不删那次全量`() {
        // 用户报的场景：全量 → user → apk+data
        // 三种内容在更新处都有，但分散在两次备份里，不能算「被替代」
        val result = deleted(
            1, 3,
            backup(1, SnapshotKind.APK, SnapshotKind.USER, SnapshotKind.DATA),
            backup(2, SnapshotKind.USER),
            backup(3, SnapshotKind.APK, SnapshotKind.DATA)
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `全量只被下一次全量替代`() {
        val result = deleted(
            1, 2,
            backup(1, SnapshotKind.APK, SnapshotKind.USER, SnapshotKind.DATA),
            backup(2, SnapshotKind.APK, SnapshotKind.USER, SnapshotKind.DATA)
        )
        assertEquals(listOf(1L), result)
    }

    @Test
    fun `新的全量会同时清掉旧全量和它之后的碎片`() {
        val result = deleted(
            1, 4,
            backup(1, SnapshotKind.APK, SnapshotKind.USER, SnapshotKind.DATA),
            backup(2, SnapshotKind.USER),
            backup(3, SnapshotKind.APK, SnapshotKind.DATA),
            backup(4, SnapshotKind.APK, SnapshotKind.USER, SnapshotKind.DATA)
        )
        assertEquals(listOf(1L, 2L, 3L), result)
    }

    // ==================== 边界 ====================

    @Test
    fun `候选全被保护时宁可不删，允许超出上限`() {
        val result = deleted(
            2, 3,
            backup(1, SnapshotKind.APK),
            backup(2, SnapshotKind.USER),
            backup(3, SnapshotKind.DATA)
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `不限时不删任何东西`() {
        val result = deleted(
            0, 3,
            backup(1, SnapshotKind.APK),
            backup(2, SnapshotKind.APK),
            backup(3, SnapshotKind.APK)
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `刚写入的行永不删，哪怕时钟回拨让它最旧`() {
        // justWritten=1 比另一行还旧：不能靠「它是最新的」来排除它
        val result = deleted(
            1, 1,
            backup(1, SnapshotKind.APK),
            backup(5, SnapshotKind.APK)
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `认不出的类型整行保护`() {
        val unknown = BackupSnapshot(
            id = "future@1",
            label = "com.test:future",
            tags = "测试|1.0.0|0|1",
            time = 1
        )
        val result = deleted(
            1, 2,
            backup(1, SnapshotKind.APK) + unknown,
            backup(2, SnapshotKind.APK)
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `图标快照不参与分组，也不会被删`() {
        val rows = Retention.rows(
            backup(1, SnapshotKind.APK) +
                    snap(SnapshotKind.ICON, 1, "com.test:icon")
        )
        assertEquals(1, rows.size)
        assertTrue(rows.single().ids.none { it.startsWith("icon") })
    }

    @Test
    fun `tag 解析不出时间时退回快照时间`() {
        val row = Retention.rows(
            listOf(
                BackupSnapshot(
                    id = "a",
                    label = "com.test:apk",
                    tags = "没有分隔符",
                    time = 100
                )
            )
        ).single()
        assertEquals(100L, row.stamp)
    }

    @Test
    fun `同一种内容超过上限时按新到旧留够份数`() {
        val result = deleted(
            2, 5,
            backup(1, SnapshotKind.APK),
            backup(2, SnapshotKind.APK),
            backup(3, SnapshotKind.APK),
            backup(4, SnapshotKind.APK),
            backup(5, SnapshotKind.APK)
        )
        assertEquals(listOf(1L, 2L, 3L), result)
    }

    @Test
    fun `每种内容互不干扰，行数可能高于上限`() {
        val result = deleted(
            2, 6,
            backup(1, SnapshotKind.APK),
            backup(2, SnapshotKind.USER),
            backup(3, SnapshotKind.DATA),
            backup(4, SnapshotKind.APK),
            backup(5, SnapshotKind.USER),
            backup(6, SnapshotKind.DATA)
        )
        // 只删被更新的同种内容替代的三行，剩下的 apk/user/data 各一份都在
        assertEquals(listOf(1L, 2L, 3L), result)
    }
}
