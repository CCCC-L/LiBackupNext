package com.cc.libackupnext.ui.theme

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ---- 动画时长常量（集中管理，方便统一调整） ----
private const val ERASE_DURATION_MS = 120L
private const val POSITION_DURATION_MS = 200L
private const val SEARCH_BAR_DURATION_MS = 200
private const val SEARCH_BAR_EXIT_DELAY_MS = 100
private const val PAGE_TRANSITION_DURATION_MS = 300
private const val ROW_COLOR_DURATION_MS = 180

/** 每个图标槽位的宽度 */
val IconSlotWidth: Dp = 48.dp

/** 右滑超过这个距离才算一次选中。 */
val SwipeTrigger: Dp = 24.dp

/** 右滑时卡片最多跟手多远。 */
val SwipeMaxOffset: Dp = 56.dp

/** 松手回弹的弹簧参数。 */
private val SwipeSpring = spring<Float>(
    dampingRatio = 0.7f,
    stiffness = Spring.StiffnessMediumLow
)

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
// ================================================================
//  右滑选中（批量页）
// ================================================================

/**
 * 右滑选中的状态：卡片跟手右移、松手弹回。
 *
 * 只认右滑（位移下限卡在 0）
 */
@Stable
class RowSwipeState internal constructor(
    private val triggerPx: Float,
    private val maxPx: Float,
    private val offset: MutableFloatState,
    private val onSwipe: () -> Unit
) {
    val offsetPx: Float get() = offset.floatValue

    val draggableState: DraggableState = DraggableState { delta ->
        offset.floatValue = (offset.floatValue + delta).coerceIn(0f, maxPx)
    }

    /** 松手：越过阈值就回调一次，然后弹回原位。 */
    suspend fun release() {
        if (offset.floatValue >= triggerPx) onSwipe()
        animate(
            initialValue = offset.floatValue,
            targetValue = 0f,
            animationSpec = SwipeSpring
        ) { value, _ -> offset.floatValue = value }
    }
}

/** 记住一份右滑选中状态；[onSwipe] 每次松手越过阈值时调用。 */
@Composable
fun rememberRowSwipeState(
    onSwipe: () -> Unit,
    trigger: Dp = SwipeTrigger,
    maxOffset: Dp = SwipeMaxOffset
): RowSwipeState {
    val density = LocalDensity.current
    val triggerPx = with(density) { trigger.toPx() }
    val maxPx = with(density) { maxOffset.toPx() }
    val offset = remember { mutableFloatStateOf(0f) }
    val latestOnSwipe = rememberUpdatedState(onSwipe)

    return remember(triggerPx, maxPx) {
        RowSwipeState(
            triggerPx = triggerPx,
            maxPx = maxPx,
            offset = offset,
            onSwipe = { latestOnSwipe.value() }
        )
    }
}

// ================================================================
//  多选选中行的底色
// ================================================================

/** 选中时整行过渡到 [selectedColor]，取消选中回到 [normalColor]。 */
@Composable
fun rememberRowSelectionColor(
    selected: Boolean,
    selectedColor: Color,
    normalColor: Color
): State<Color> = animateColorAsState(
    targetValue = if (selected) selectedColor else normalColor,
    animationSpec = tween(ROW_COLOR_DURATION_MS),
    label = "rowSelectionColor"
)

// ================================================================
//  页面层级切换（列表 ↔ 详情 ↔ 批量页 …）
// ================================================================

/**
 * 层级越深越靠右：进入时从右滑入，返回时反向。
 *
 * @param depthOf 给每个页面一个层级值，越大表示越靠里（例如枚举的 ordinal）。
 */
fun <S> pageTransitionSpec(
    depthOf: (S) -> Int
): AnimatedContentTransitionScope<S>.() -> ContentTransform = {
    if (depthOf(targetState) > depthOf(initialState)) {
        (slideInHorizontally(
            initialOffsetX = { it },
            animationSpec = tween(PAGE_TRANSITION_DURATION_MS)
        ) + fadeIn(animationSpec = tween(PAGE_TRANSITION_DURATION_MS))) togetherWith
                (slideOutHorizontally(
                    targetOffsetX = { -it / 3 },
                    animationSpec = tween(PAGE_TRANSITION_DURATION_MS)
                ) + fadeOut(animationSpec = tween(PAGE_TRANSITION_DURATION_MS)))
    } else {
        (slideInHorizontally(
            initialOffsetX = { -it / 3 },
            animationSpec = tween(PAGE_TRANSITION_DURATION_MS)
        ) + fadeIn(animationSpec = tween(PAGE_TRANSITION_DURATION_MS))) togetherWith
                (slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(PAGE_TRANSITION_DURATION_MS)
                ) + fadeOut(animationSpec = tween(PAGE_TRANSITION_DURATION_MS)))
    }.using(SizeTransform(clip = false))
}
