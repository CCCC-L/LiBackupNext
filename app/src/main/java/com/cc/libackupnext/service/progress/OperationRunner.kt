package com.cc.libackupnext.service.progress

import com.cc.libackupnext.data.BackupStep
import com.cc.libackupnext.data.BackupProgress
import com.cc.libackupnext.service.gateway.BackendErrors
import com.cc.libackupnext.service.gateway.RemoteInterruptedException
import com.cc.libackupnext.util.AppLog

/**
 * 一次操作（备份 / 覆盖 / 恢复）跑完之后的分类结果。
 *
 * 分三类而不是 `Result`：调用方对这三种情况的处理完全不同 ——
 * 成功要刷新列表、失败要留在弹窗里显示原因、断连要停批并提示重连。
 * 用 `Result.failure` 装三种含义，每个调用点都得自己再判一次断连。
 */
sealed interface OperationOutcome {

    /**
     * 成功。
     *
     * 不携带返回值：备份返回清理条数、恢复返回 Unit、删除返回删除数量，
     * 三者语义不同，强行统一只会逼调用方写 `.map { 0 }`。
     * 需要具体结果的地方在 `execute` 里就地处理（比如清理条数的 toast）。
     */
    data object Success : OperationOutcome

    /** 普通失败：参数错误、仓库报错。调用方显示 [error] 的 message。 */
    data class Failed(val error: Throwable) : OperationOutcome

    /** 远程仓库断连：调用方要停掉后续批次并提示重连，而不是当成普通失败。 */
    data class Interrupted(val error: Throwable) : OperationOutcome
}

/**
 * 一次操作的执行骨架：**推进度 → 执行 → 分类结果 → 失败标记**。
 *
 * 从详情页和批量页各写一遍的流程里抽出来。原来这两处都在各自的 Composable 里
 * 手写「applyProgress + 断连判定 + markFailed + 设置错误文案」，
 * 逻辑一样但容易改一处漏一处（尤其是断连判定，漏了就不会停批）。
 *
 * 刻意**不碰通知**：通知需要 Context、应用名、批次序号，属于 UI 关注点。
 * 调用方在 `onProgress` 里顺带更新通知即可，runner 只管步骤模型和结果分类。
 *
 * 断连时后端会先写一帧 `interrupted`：这里立刻冻结进度（丢后续帧），但结论仍由
 * `execute` 的返回值给出 —— 早报错会让用户在第一笔收尾期间就发起第二笔。
 */
class OperationRunner(private val planner: OperationPlanner) {

    /**
     * @param steps 这次操作的初始步骤（备份用 backupSteps，恢复用 restoreSteps）
     * @param onSteps 步骤变化回调；调用方负责把它写进 Compose 状态
     * @param execute 真正执行的地方，回调参数用来上报进度
     */
    suspend fun <T> run(
        steps: List<BackupStep>,
        onSteps: (List<BackupStep>) -> Unit,
        execute: suspend (onProgress: (BackupProgress) -> Unit) -> Result<T>,
    ): OperationOutcome {
        var current = steps
        onSteps(current)

        // 字节帧来自 binder 线程、传输帧来自轮询协程，都要读改写 steps，不加锁会互相覆盖
        val lock = Any()

        // 中断帧之后一律丢弃：断连时 rustic 还在读源文件，不丢条会一直涨
        var interrupted = false
        var interruptedText: String? = null

        val result = execute { progress ->
            synchronized(lock) {
                if (interrupted) return@synchronized
                // 中断帧不进 planner：步骤保持原样，之后的帧一律丢弃，条就停在判定那一刻
                if (progress.state == BackupProgress.STATE_INTERRUPTED) {
                    interrupted = true
                    interruptedText = progress.interruptedText
                    return@synchronized
                }
                current = planner.applyProgress(current, progress)
                onSteps(current)
            }
        }

        return result.fold(
            onSuccess = {
                // 理论上不会发生（cancel 后写不进仓库）；真出现也按断连收尾，
                // 免得界面刚报完错又翻成「成功」
                interruptedText
                    ?.let { OperationOutcome.Interrupted(RemoteInterruptedException(it)) }
                    ?: OperationOutcome.Success
            },
            onFailure = { error ->
                if (BackendErrors.isConnectionLost(error)) {
                    // 断连不标失败：进度条保持原样，用户看到的是「连接中断」而不是「这一步失败了」
                    OperationOutcome.Interrupted(error)
                } else {
                    onSteps(planner.markFailed(current))
                    OperationOutcome.Failed(error)
                }
            }
        )
    }
}
