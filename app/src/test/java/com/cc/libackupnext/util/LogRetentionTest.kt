package com.cc.libackupnext.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 钉住轮转改名顺序和崩溃报告淘汰规则：这两处算错都不报错，只能靠测试发现。 */
class LogRetentionTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 1_700_000_000_000L

    // ==================== 轮转顺序 ====================

    @Test
    fun `两份备份时从最老的开始挪，当前文件最后挪`() {
        val plan = LogRetention.rotationPlan("main.log", 2)

        assertEquals(
            listOf(
                "main.log.1" to "main.log.2",
                "main.log" to "main.log.1"
            ),
            plan
        )
        // 当前文件最后挪，否则没有来源可覆盖
        assertEquals("main.log", plan.last().first)
    }

    @Test
    fun `只留一份备份时直接挪成 1`() {
        assertEquals(
            listOf("main.log" to "main.log.1"),
            LogRetention.rotationPlan("main.log", 1)
        )
    }

    @Test
    fun `不留备份时什么都不做`() {
        assertTrue(LogRetention.rotationPlan("main.log", 0).isEmpty())
        assertTrue(LogRetention.rotationPlan("main.log", -1).isEmpty())
    }

    @Test
    fun `按计划执行后内容不错位，最老的被挤掉`() {
        // 模拟文件系统：.2 最老、当前最新
        val fs = mutableMapOf(
            "main.log" to "C",
            "main.log.1" to "B",
            "main.log.2" to "A"
        )

        LogRetention.rotationPlan("main.log", 2).forEach { (from, to) ->
            val content = fs.remove(from) ?: return@forEach
            fs[to] = content
        }

        assertEquals("C", fs["main.log.1"])
        assertEquals("B", fs["main.log.2"])
        assertNull(fs["main.log"])      // 已挪走，下次写入时新建
        assertNull(fs["main.log.3"])    // 最老的 A 被挤掉，不会多出一份
        assertEquals(2, fs.size)
    }

    // ==================== 崩溃报告淘汰 ====================

    private fun crash(name: String, ageMillis: Long) =
        LogRetention.CrashFile(name, now - ageMillis)

    @Test
    fun `超过 7 天的崩溃报告全部删掉`() {
        val victims = LogRetention.crashesToDelete(
            crashes = listOf(
                crash("crash-old.txt", 8 * day),
                crash("crash-edge.txt", 7 * day + 1),
                crash("crash-fresh.txt", day)
            ),
            maxFiles = 10,
            now = now,
            retainMillis = 7 * day
        )

        assertEquals(setOf("crash-old.txt", "crash-edge.txt"), victims.toSet())
    }

    @Test
    fun `数量超限时保留最新的`() {
        // 编号越小越新
        val crashes = (1..15).map { crash("crash-$it.txt", it * 1000L) }

        val victims = LogRetention.crashesToDelete(crashes, maxFiles = 10, now = now, retainMillis = 7 * day)

        assertEquals(5, victims.size)
        assertEquals(
            setOf(
                "crash-11.txt", "crash-12.txt", "crash-13.txt",
                "crash-14.txt", "crash-15.txt"
            ),
            victims.toSet()
        )
    }

    @Test
    fun `过期和超量叠加时两类都删`() {
        val crashes = listOf(
            crash("crash-expired.txt", 10 * day)
        ) + (1..12).map { crash("crash-$it.txt", it * 1000L) }

        val victims = LogRetention.crashesToDelete(crashes, maxFiles = 10, now = now, retainMillis = 7 * day)

        // 1 个过期 + 12 个新鲜里超出 10 份的 2 个
        assertTrue("crash-expired.txt" in victims)
        assertEquals(2, victims.count { it != "crash-expired.txt" })
        assertEquals(3, victims.size)
    }

    @Test
    fun `正好等于上限时一个都不删`() {
        val crashes = (1..10).map { crash("crash-$it.txt", it * 1000L) }

        assertTrue(
            LogRetention.crashesToDelete(crashes, maxFiles = 10, now = now, retainMillis = 7 * day)
                .isEmpty()
        )
    }

    @Test
    fun `没有崩溃报告时返回空`() {
        assertTrue(
            LogRetention.crashesToDelete(emptyList(), 10, now, 7 * day).isEmpty()
        )
    }
}
