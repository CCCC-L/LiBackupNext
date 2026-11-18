package com.cc.libackupnext.service

import com.cc.libackupnext.data.AppPrefs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine

/** 用户设置：只描述「用户想不想常亮」，不掺任何操作状态。 */
interface ScreenOnSetting {
    val enabled: StateFlow<Boolean>
    fun setEnabled(value: Boolean)
}

/**
 * 屏幕常亮的最终决策。
 */
interface ScreenOnDecision {
    val shouldKeepOn: Flow<Boolean>
}

internal object DefaultScreenOnSetting : ScreenOnSetting {
    private val _enabled = MutableStateFlow(AppPrefs.keepScreenOn)
    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    override fun setEnabled(value: Boolean) {
        if (_enabled.value == value) return
        _enabled.value = value
        AppPrefs.keepScreenOn = value
    }
}

/** 决策只依赖两个窄接口，不直接认识 AppPrefs / BackupNotifier。 */
internal class DefaultScreenOnDecision(
    private val operation: OperationState,
    private val setting: ScreenOnSetting
) : ScreenOnDecision {
    override val shouldKeepOn: Flow<Boolean> =
        combine(operation.isRunning, setting.enabled) { running, enabled ->
            running && enabled
        }
}

/** 组合根：用委托拼装，不用继承。 */
object ScreenOnService : ScreenOnSetting by DefaultScreenOnSetting,
    ScreenOnDecision by DefaultScreenOnDecision(operationState, DefaultScreenOnSetting)

/** 设置页只拿得到写设置的能力。 */
val screenOnSetting: ScreenOnSetting get() = ScreenOnService

/** 展示页只拿得到最终决策。 */
val screenOnDecision: ScreenOnDecision get() = ScreenOnService
