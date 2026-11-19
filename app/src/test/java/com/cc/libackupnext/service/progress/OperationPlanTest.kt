package com.cc.libackupnext.service.progress

import com.cc.libackupnext.data.BackupEntry
import com.cc.libackupnext.data.BackupGroup
import com.cc.libackupnext.data.BackupSnapshot
import com.cc.libackupnext.data.BackupStepStatus
import com.cc.libackupnext.data.SnapshotKind
import com.cc.libackupnext.data.BackupProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 进度模型的纯函数测试：不起 Android、不碰仓库、不绑 root 服务。
 *
 * 这些逻辑原来长在 `RusticClient` 里，跟绑定服务和拼请求混在一起，
 * 一行都测不了。搬进 [DefaultOperationPlanner] 之后才有这组用例 —— 这也是拆分的直接收益。
 */
class OperationPlanTest {

    // ==================== 造数据 ====================

    private fun snap(kind: SnapshotKind) = BackupSnapshot(
        id = "${kind.key}-id",
        label = "com.test:${kind.key}",
        tags = "测试|1.0.0|0|100",
        time = 100
    )

    private fun entry(vararg kinds: SnapshotKind) =
        BackupEntry(title = "测试", time = 100, snapshots = kinds.map { snap(it) })

    private fun progress(stage: String, state: String) =
        BackupProgress(stage = stage, state = state, files = 3, reason = "r")

    // ==================== 备份步骤 ====================

    @Test
    fun `备份步骤只包含勾选的分组，且按固定顺序排列`() {
        val steps = DefaultOperationPlanner.backupSteps(setOf(BackupGroup.DATA, BackupGroup.APK))

        // APK 分组展开成 apk / obb / permissions，加 data；顺序固定
        assertEquals(
            listOf(
                SnapshotKind.APK,
                SnapshotKind.OBB,
                SnapshotKind.PERMISSIONS,
                SnapshotKind.DATA
            ),
            steps.map { it.kind }
        )
        assertTrue(steps.all { it.status == BackupStepStatus.WAITING })
    }

    @Test
    fun `系统数据排在应用内容之后`() {
        val steps = DefaultOperationPlanner.backupSteps(
            setOf(BackupGroup.WIFI, BackupGroup.APK, BackupGroup.SMS, BackupGroup.MMS)
        )
        val kinds = steps.map { it.kind }

        assertTrue(
            "Wi-Fi 必须排在 apk 之后",
            kinds.indexOf(SnapshotKind.WIFI) > kinds.indexOf(SnapshotKind.APK)
        )
        assertTrue("短信必须先于彩信", kinds.indexOf(SnapshotKind.SMS) < kinds.indexOf(SnapshotKind.MMS))
    }

    @Test
    fun `没有勾选任何分组时步骤为空`() {
        assertTrue(DefaultOperationPlanner.backupSteps(emptySet()).isEmpty())
    }

    // ==================== 恢复步骤 ====================

    @Test
    fun `恢复步骤只列出这条备份里真实存在的快照`() {
        val entry = entry(SnapshotKind.APK, SnapshotKind.USER)
        // 勾了 data，但这条备份里没有 data 快照
        val steps = DefaultOperationPlanner.restoreSteps(
            entry,
            setOf(BackupGroup.APK, BackupGroup.USER, BackupGroup.DATA)
        )

        assertEquals(listOf(SnapshotKind.APK, SnapshotKind.USER), steps.map { it.kind })
    }

    // ==================== 进度合并 ====================

    @Test
    fun `进度事件只更新匹配的那一步`() {
        val steps = DefaultOperationPlanner.backupSteps(setOf(BackupGroup.APK))
        val running = DefaultOperationPlanner.applyProgress(steps, progress("apk", "running"))

        val apk = running.first { it.kind == SnapshotKind.APK }
        assertEquals(BackupStepStatus.RUNNING, apk.status)
        assertEquals(3, apk.files)

        // 其它步骤不受影响
        assertTrue(
            running.filter { it.kind != SnapshotKind.APK }
                .all { it.status == BackupStepStatus.WAITING }
        )
    }

    @Test
    fun `空 stage 的进度事件不改任何步骤`() {
        val steps = DefaultOperationPlanner.backupSteps(setOf(BackupGroup.APK))
        assertEquals(steps, DefaultOperationPlanner.applyProgress(steps, progress("", "done")))
    }

    @Test
    fun `未知 state 保留原来的状态，不倒退`() {
        val steps = DefaultOperationPlanner.backupSteps(setOf(BackupGroup.APK))
        val running = DefaultOperationPlanner.applyProgress(steps, progress("apk", "running"))
        val unknown = DefaultOperationPlanner.applyProgress(running, progress("apk", "whatever"))

        assertEquals(
            BackupStepStatus.RUNNING,
            unknown.first { it.kind == SnapshotKind.APK }.status
        )
    }

    // ==================== 失败标记 ====================

    @Test
    fun `失败只标记未完成的步骤，已完成的保持原样`() {
        var steps = DefaultOperationPlanner.backupSteps(setOf(BackupGroup.APK, BackupGroup.DATA))
        steps = DefaultOperationPlanner.applyProgress(steps, progress("apk", "done"))
        steps = DefaultOperationPlanner.applyProgress(steps, progress("obb", "running"))

        val failed = DefaultOperationPlanner.markFailed(steps)

        assertEquals(BackupStepStatus.DONE, failed.first { it.kind == SnapshotKind.APK }.status)
        assertEquals(BackupStepStatus.FAILED, failed.first { it.kind == SnapshotKind.OBB }.status)
        assertEquals(
            BackupStepStatus.FAILED,
            failed.first { it.kind == SnapshotKind.PERMISSIONS }.status
        )
    }

    // ==================== 恢复前置判断 ====================

    @Test
    fun `没勾选任何内容时不允许恢复`() {
        val reason = DefaultOperationPlanner.restoreBlockedReason(entry(SnapshotKind.APK), emptySet(), true)
        assertNotNull(reason)
    }

    @Test
    fun `只恢复目录时不要求应用已安装`() {
        val reason = DefaultOperationPlanner.restoreBlockedReason(
            entry(SnapshotKind.DIR),
            setOf(BackupGroup.DIR),
            installed = false
        )
        assertNull(reason)
    }

    @Test
    fun `只恢复系统数据时不要求应用已安装`() {
        val reason = DefaultOperationPlanner.restoreBlockedReason(
            entry(SnapshotKind.WIFI),
            setOf(BackupGroup.WIFI),
            installed = false
        )
        assertNull(reason)
    }

    @Test
    fun `只勾了 apk 且应用未安装时可以恢复`() {
        val reason = DefaultOperationPlanner.restoreBlockedReason(
            entry(SnapshotKind.APK),
            setOf(BackupGroup.APK),
            installed = false
        )
        assertNull(reason)
    }

    @Test
    fun `要恢复应用数据但没勾 apk 且未安装时拒绝`() {
        val reason = DefaultOperationPlanner.restoreBlockedReason(
            entry(SnapshotKind.USER),
            setOf(BackupGroup.USER),
            installed = false
        )
        assertEquals("应用未安装，无法恢复数据", reason)
    }

    @Test
    fun `要恢复应用数据且应用已安装时放行`() {
        val reason = DefaultOperationPlanner.restoreBlockedReason(
            entry(SnapshotKind.USER),
            setOf(BackupGroup.USER),
            installed = true
        )
        assertNull(reason)
    }

    // ==================== 覆盖 ====================

    @Test
    fun `应用没装时不允许覆盖备份`() {
        assertTrue(DefaultOperationPlanner.canOverwrite(installed = true))
        assertTrue(!DefaultOperationPlanner.canOverwrite(installed = false))
    }
}
