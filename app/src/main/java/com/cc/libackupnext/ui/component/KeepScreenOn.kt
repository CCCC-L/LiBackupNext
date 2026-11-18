package com.cc.libackupnext.ui.component

import androidx.compose.ui.Modifier
import androidx.compose.ui.keepScreenOn

/**
 * 把「是否保持常亮」这个状态翻译成 UI 修饰器。
 *
 * 页面只管把状态传进来，不关心状态是怎么算出来的，也不直接碰 Window / WakeLock。
 */
fun Modifier.keepScreenOnWhen(enabled: Boolean): Modifier =
    then(if (enabled) Modifier.keepScreenOn() else Modifier)
