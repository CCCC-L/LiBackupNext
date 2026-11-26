package com.cc.libackupnext.service.progress

import com.cc.libackupnext.data.BackupGroup
import com.cc.libackupnext.data.BackupStep
import com.cc.libackupnext.data.BackupStepStatus
import com.cc.libackupnext.data.SnapshotKind
import com.cc.libackupnext.data.BackupProgress
import com.cc.libackupnext.service.gateway.RemoteInterruptedException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 操作骨架的行为测试。
 *
 * 重点验证**断连和普通失败要分开处理**：断连时进度条不能标红
 * （用户看到的是「连接中断」而不是「这一步失败了」），这条如果回归，
 * 用户会以为备份数据写坏了。
 */
class OperationRunnerTest {

    private val runner = OperationRunner(DefaultOperationPlanner)

    private fun apkSteps(): List<BackupStep> =
        DefaultOperationPlanner.backupSteps(setOf(BackupGroup.APK))

    @Test
    fun `成功时返回 Success，并把进度合并进步骤`() = runBlocking {
        var observed: List<BackupStep> = emptyList()

        val outcome = runner.run(
            steps = apkSteps(),
            onSteps = { observed = it }
        ) { onProgress ->
            onProgress(BackupProgress(stage = "apk", state = "done", files = 5))
            Result.success(3)
        }

        assertEquals(OperationOutcome.Success, outcome)
        val apk = observed.first { it.kind == SnapshotKind.APK }
        assertEquals(BackupStepStatus.DONE, apk.status)
        assertEquals(5, apk.files)
    }

    @Test
    fun `普通失败时把未完成的步骤标成失败`() = runBlocking {
        var observed: List<BackupStep> = emptyList()
        val error = IllegalStateException("仓库报错")

        val outcome = runner.run(
            steps = apkSteps(),
            onSteps = { observed = it }
        ) { _ ->
            Result.failure<Unit>(error)
        }

        assertTrue(outcome is OperationOutcome.Failed)
        assertEquals(error, (outcome as OperationOutcome.Failed).error)
        // 全部步骤都还没跑完，应该都被标成失败
        assertTrue(observed.all { it.status == BackupStepStatus.FAILED })
    }

    @Test
    fun `断连时不标失败，返回 Interrupted`() = runBlocking {
        var observed: List<BackupStep> = emptyList()
        // 断连是类型判定，不是文案相等：message 是具体原因（「无法连接到远程服务器」等）
        val error = RemoteInterruptedException("无法连接到远程服务器")

        val outcome = runner.run(
            steps = apkSteps(),
            onSteps = { observed = it }
        ) { _ ->
            Result.failure<Unit>(error)
        }

        assertTrue(outcome is OperationOutcome.Interrupted)
        // 关键：断连不是「这一步失败」，进度条保持原样
        assertTrue(
            "断连不该把步骤标成失败",
            observed.all { it.status == BackupStepStatus.WAITING }
        )
    }

    @Test
    fun `执行前会先回调一次初始步骤`() = runBlocking {
        val callbacks = mutableListOf<List<BackupStep>>()

        runner.run(
            steps = apkSteps(),
            onSteps = { callbacks += it }
        ) { Result.success(0) }

        // 第一次回调是初始状态，UI 靠它把步骤列表填进弹窗
        assertTrue(callbacks.isNotEmpty())
        assertTrue(callbacks.first().all { it.status == BackupStepStatus.WAITING })
    }

    @Test
    fun `部分步骤完成后失败，只把剩下的标成失败`() = runBlocking {
        var observed: List<BackupStep> = emptyList()

        val outcome = runner.run(
            steps = apkSteps(),
            onSteps = { observed = it }
        ) { onProgress ->
            onProgress(BackupProgress(stage = "apk", state = "done"))
            Result.failure<Unit>(IllegalStateException("obb 写失败"))
        }

        assertTrue(outcome is OperationOutcome.Failed)
        // apk 已经完成，不该被回退成失败
        assertEquals(
            BackupStepStatus.DONE,
            observed.first { it.kind == SnapshotKind.APK }.status
        )
        assertEquals(
            BackupStepStatus.FAILED,
            observed.first { it.kind == SnapshotKind.OBB }.status
        )
    }

    /** 中断帧一进来就回调；之后涌进来的字节帧必须丢掉，否则条会在等 native 时继续涨。 */
    @Test
    fun `中断帧立刻回调，并且之后的进度帧不再推进`() = runBlocking {
        var observed: List<BackupStep> = emptyList()
        val reasons = mutableListOf<String>()

        val outcome = runner.run(
            steps = apkSteps(),
            onSteps = { observed = it },
            onInterrupted = { reasons += it }
        ) { onProgress ->
            onProgress(BackupProgress(stage = "apk", state = "running"))
            onProgress(
                BackupProgress(
                    stage = "apk",
                    state = BackupProgress.STATE_PROGRESS,
                    total = 1_000,
                    current = 400
                )
            )
            onProgress(
                BackupProgress(
                    stage = "apk",
                    state = BackupProgress.STATE_INTERRUPTED,
                    reason = "unreachable"
                )
            )
            // 断连之后 rustic 还在读源文件，字节帧照来：必须被丢掉
            onProgress(
                BackupProgress(
                    stage = "apk",
                    state = BackupProgress.STATE_PROGRESS,
                    total = 1_000,
                    current = 999
                )
            )
            Result.failure<Unit>(RemoteInterruptedException("无法连接到远程服务器"))
        }

        // 只回调一次，且文案查表而不是把 wire 原样给出去
        assertEquals(listOf("无法连接到远程服务器"), reasons)
        val apk = observed.first { it.kind == SnapshotKind.APK }
        assertEquals(400L, apk.current)
        // 状态保持 RUNNING：一标失败，进度区就找不到「当前步骤」，条会退回不确定态
        assertEquals(BackupStepStatus.RUNNING, apk.status)
        assertEquals("无法连接到远程服务器", apk.reason)
        assertTrue(outcome is OperationOutcome.Interrupted)
    }
}
