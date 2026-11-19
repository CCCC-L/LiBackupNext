package com.cc.libackupnext.data.target

import com.cc.libackupnext.data.BackupTarget
import com.cc.libackupnext.data.SnapshotKind
import com.cc.libackupnext.data.AppInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 注册表一致性：这是「加一种类型只改一处」这个承诺的护栏。
 *
 * 没有这组测试时，漏注册的后果是运行时 `TargetSpecs.of()` 抛异常、
 * 或者界面上少一行图标 —— 都要装到手机上才发现。有了它，`./gradlew test` 就会拦住。
 */
class TargetSpecsTest {

    private fun app(
        packageName: String = "com.test",
        isFrozen: Boolean = false,
        onlyInBackup: Boolean = false
    ) = AppInfo(
        name = "测试",
        packageName = packageName,
        version = "1.0.0",
        versionCode = 1,
        installTime = 0,
        updateTime = 0,
        userId = 0,
        isSystem = false,
        isFrozen = isFrozen,
        onlyInBackup = onlyInBackup
    )

    // ==================== 覆盖率 ====================

    @Test
    fun `每个 BackupTarget 都必须注册 spec`() {
        BackupTarget.entries.forEach { target ->
            // of() 找不到会 error()，这条断言就是「漏注册就红」
            assertNotNull("$target 没有注册 TargetSpec", TargetSpecs.of(target))
        }
    }

    @Test
    fun `底部导航分组覆盖全部目标，且不重复`() {
        val grouped = TargetNavGroup.entries
            .flatMap { TargetSpecs.byNavGroup(it) }

        assertEquals(
            "分组后的目标应恰好等于全部目标",
            BackupTarget.entries.toSet(),
            grouped.toSet()
        )
        assertEquals("同一个目标不能出现在两个分组", grouped.size, grouped.toSet().size)
    }

    @Test
    fun `固定目标都归在「其他」分组`() {
        BackupTarget.entries.filter { it.isFixed }.forEach { target ->
            assertEquals(
                "$target 是固定目标，应归在 OTHER",
                TargetNavGroup.OTHER,
                TargetSpecs.of(target).navGroup
            )
        }
    }

    // ==================== 字段完整性 ====================

    @Test
    fun `每条 spec 的文案与图标名都不为空`() {
        TargetSpecs.specs().forEach { spec ->
            assertTrue("${spec.target} 的 label 为空", spec.label.isNotBlank())
            assertTrue("${spec.target} 的 detailTitle 为空", spec.detailTitle.isNotBlank())
            assertTrue("${spec.target} 的 iconKey 为空", spec.iconKey.isNotBlank())
        }
    }

    @Test
    fun `只有应用目标使用应用自己的图标`() {
        val appIconUsers = TargetSpecs.specs()
            .filter { it.iconKey == TargetSpecs.APP_ICON_KEY }
            .map { it.target }

        assertEquals(listOf(BackupTarget.APP), appIconUsers)
    }

    @Test
    fun `除应用和目录外，每种目标都有帮助文案`() {
        TargetSpecs.specs()
            .filter { it.viewKind == TargetViewKind.SYSTEM }
            .forEach { spec ->
                assertTrue("${spec.target} 没有帮助文案", spec.helpLines.isNotEmpty())
            }
    }

    // ==================== 行为 ====================

    @Test
    fun `除短彩信外每种目标都有菜单动作`() {
        // 短彩信没有「打开系统对应界面」这种动作，是既有的产品决定，不是漏配
        TargetSpecs.specs()
            .filter { it.target != BackupTarget.SMS }
            .forEach { spec ->
                assertTrue(
                    "${spec.target} 在详情页没有任何可点的动作",
                    spec.actions(app()).isNotEmpty()
                )
            }
    }

    @Test
    fun `短彩信没有菜单动作`() {
        assertTrue(TargetSpecs.of(BackupTarget.SMS).actions(app()).isEmpty())
    }

    @Test
    fun `冻结状态决定菜单里是冻结还是解冻`() {
        val actions = TargetSpecs.of(BackupTarget.APP).actions

        assertTrue(actions(app(isFrozen = false)).contains(ItemAction.FREEZE))
        assertFalse(actions(app(isFrozen = false)).contains(ItemAction.UNFREEZE))

        assertTrue(actions(app(isFrozen = true)).contains(ItemAction.UNFREEZE))
        assertFalse(actions(app(isFrozen = true)).contains(ItemAction.FREEZE))
    }

    @Test
    fun `应用只在备份里时标记为不存在`() {
        val spec = TargetSpecs.of(BackupTarget.APP)
        assertTrue(spec.isMissing(app(onlyInBackup = true)))
        assertFalse(spec.isMissing(app(onlyInBackup = false)))
    }

    @Test
    fun `目录不存在时标记为不存在`() {
        val spec = TargetSpecs.of(BackupTarget.DIR)
        assertTrue(spec.isMissing(app(packageName = "/definitely/not/here")))
    }

    @Test
    fun `系统数据永远不标记为不存在`() {
        TargetSpecs.specs()
            .filter { it.viewKind == TargetViewKind.SYSTEM }
            .forEach { spec ->
                assertFalse(
                    "${spec.target} 不该被标成不存在",
                    spec.isMissing(app())
                )
            }
    }

    // ==================== 计数文案 ====================

    @Test
    fun `Wi-Fi 和联系人用不同的量词`() {
        val counts = mapOf(SnapshotKind.WIFI to 12, SnapshotKind.CONTACTS to 34)

        assertEquals("已保存 12 个", TargetSpecs.of(BackupTarget.WIFI).countText(counts))
        assertEquals("共 34 个", TargetSpecs.of(BackupTarget.CONTACTS).countText(counts))
    }

    @Test
    fun `读不到条数时显示破折号`() {
        assertEquals("—", TargetSpecs.of(BackupTarget.WIFI).countText(emptyMap()))
        assertEquals("—", TargetSpecs.of(BackupTarget.CALLS).countText(emptyMap()))
    }

    @Test
    fun `短彩信把短信和彩信分开列`() {
        val counts = mapOf(SnapshotKind.SMS to 22, SnapshotKind.MMS to 2)
        assertEquals("短信 22 · 彩信 2", TargetSpecs.of(BackupTarget.SMS).countText(counts))
    }

    @Test
    fun `应用和目录没有计数文案`() {
        assertEquals("", TargetSpecs.of(BackupTarget.APP).countText(emptyMap()))
        assertEquals("", TargetSpecs.of(BackupTarget.DIR).countText(emptyMap()))
    }
}
