package com.cc.libackupnext.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ---- 动画时长常量（集中管理，方便统一调整） ----
private const val ERASE_DURATION_MS = 120L
private const val POSITION_DURATION_MS = 200L
private const val SEARCH_BAR_DURATION_MS = 200
private const val SEARCH_BAR_EXIT_DELAY_MS = 100

/** 每个图标槽位的宽度 */
val IconSlotWidth: Dp = 48.dp

// ================================================================
//  图标擦除动画（搜索 / 更多 / 将来任何需要"擦除消失"的图标都可用）
// ================================================================

/**
 * 图标擦除进度：1f 表示完整显示，0f 表示完全擦除。
 *
 * 每个调用点都会得到一份独立的动画状态，多个图标之间互不影响，
 * 因此可以给每个图标单独控制自己的消失/出现时机。
 */
@Composable
fun rememberEraseProgress(visible: Boolean): State<Float> =
    animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = ERASE_DURATION_MS.toInt()),
        label = "eraseProgress"
    )

/**
 * 图标擦除效果的 Modifier：
 * - 宽度随 progress 从 [IconSlotWidth] 收缩到 0
 * - [clipToBounds] 让内容从右侧被裁掉（PPT 擦除效果）
 * - 内部 IconButton 需要用 `Modifier.requiredWidth(IconSlotWidth)` 保持原始宽度
 */
fun Modifier.eraseToRight(progress: Float): Modifier = this
    .width(IconSlotWidth * progress)
    .clipToBounds()

// ================================================================
//  图标位置移动动画（排序按钮在进入/退出搜索时，向右滑到"更多"位置）
// ================================================================

/**
 * 中间按钮在非搜索态时，与右端的间距（让出更多按钮的位置）。
 * 搜索态时归零，让中间按钮移动到最右端。
 */
@Composable
fun rememberMiddleButtonEndPadding(isSearching: Boolean): State<Dp> =
    animateDpAsState(
        targetValue = if (isSearching) 0.dp else IconSlotWidth,
        animationSpec = tween(durationMillis = POSITION_DURATION_MS.toInt()),
        label = "middleButtonEndPadding"
    )

/**
 * 中间按钮 + 更多按钮的容器宽度。
 * 非搜索态：两个图标位（因为有两个按钮）。
 * 搜索态：一个图标位（只剩中间按钮）。
 */
@Composable
fun rememberActionGroupWidth(isSearching: Boolean): State<Dp> =
    animateDpAsState(
        targetValue = if (isSearching) IconSlotWidth else IconSlotWidth * 2,
        animationSpec = tween(durationMillis = ERASE_DURATION_MS.toInt()),
        label = "actionGroupWidth"
    )

// ================================================================
//  搜索栏进出动画（同时供 AppListScreen 和 AppBackupScreen 使用）
// ================================================================

/** 搜索栏入场：从左滑入 + 淡入 */
fun searchBarEnterTransition(): EnterTransition =
    fadeIn(animationSpec = tween(SEARCH_BAR_DURATION_MS)) +
            slideInHorizontally(
                initialOffsetX = { -it / 3 },
                animationSpec = tween(SEARCH_BAR_DURATION_MS)
            )

/** 搜索栏出场：向左滑出 + 淡出（延迟 [SEARCH_BAR_EXIT_DELAY_MS]，让内部文字先消失） */
fun searchBarExitTransition(): ExitTransition =
    fadeOut(
        animationSpec = tween(SEARCH_BAR_DURATION_MS, delayMillis = SEARCH_BAR_EXIT_DELAY_MS)
    ) + slideOutHorizontally(
        targetOffsetX = { -it / 3 },
        animationSpec = tween(SEARCH_BAR_DURATION_MS, delayMillis = SEARCH_BAR_EXIT_DELAY_MS)
    )