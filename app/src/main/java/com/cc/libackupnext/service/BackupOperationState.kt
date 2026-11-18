package com.cc.libackupnext.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 进行中状态的只读视图：策略层只依赖它，不关心谁在发起操作。 */
interface OperationState {
    val isRunning: StateFlow<Boolean>
}

/** 操作生命周期：只有发起方拿得到，UI 接触不到。 */
interface OperationLifecycle {
    fun begin()
    fun end()
}

/**
 * 备份 / 恢复的进行中状态。
 *
 * 单独从通知里拆出来，是因为「操作是否进行中」不只通知要用（屏幕常亮也要用）。
 * 计数而不是布尔值：批量页和详情页理论上可能重叠，先结束的那个不该把状态清零。
 */
internal object BackupOperationState : OperationState, OperationLifecycle {
    private var active = 0
    private val _isRunning = MutableStateFlow(false)
    override val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    @Synchronized
    override fun begin() {
        active++
        _isRunning.value = true
    }

    @Synchronized
    override fun end() {
        active = (active - 1).coerceAtLeast(0)
        _isRunning.value = active > 0
    }
}

/** 对外只暴露窄接口，消费方无法反向改状态。 */
val operationState: OperationState get() = BackupOperationState
internal val operationLifecycle: OperationLifecycle get() = BackupOperationState
