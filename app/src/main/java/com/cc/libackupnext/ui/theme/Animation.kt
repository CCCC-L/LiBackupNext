package com.cc.libackupnext.ui.theme

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColor
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateMeasurement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

// ---- 动画时长常量（集中管理，方便统一调整） ----
private const val ERASE_DURATION_MS = 800L
/**
 * 搜索栏弹出 / 收起时，按钮右移一个图标位的时长。
 * 用户按钮和排序按钮共用这一个 tween，两处同时起步、同时到位。
 */
private const val POSITION_DURATION_MS = 750L
/** 搜索栏展开 / 收缩的时长；顶栏等它收完再弹键盘也用这个值。 */
const val SearchBarDurationMs = 800
/** 搜索栏弹出时：搜索图标、更多按钮在槽内擦除消失的时长（比搜索栏本身略快）。 */
const val SearchButtonHideMs = 750
/** 引导页 Root 卡片转场时长。 */
const val WelcomeTransitionMs = 300
/** 点击底栏切页签时，Pager 滚动到目标页的时长。 */
const val TabScrollDurationMs = 800
private const val PAGE_TRANSITION_DURATION_MS = 800
private const val ROW_COLOR_DURATION_MS = 180
/** 未完成批次的闪红呼吸周期（单程）。 */
private const val PENDING_RETRY_PULSE_DURATION_MS = 500

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
fun rememberEraseProgress(
    visible: Boolean,
    durationMs: Int = ERASE_DURATION_MS.toInt()
): State<Float> =
    animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = durationMs),
        label = "eraseProgress"
    )

/**
 * 0f —— 不做右移，和搜索框的展开一直同步到最后一帧。
 */
private const val ERASE_FLY_FRACTION = 0f

/**
 * 图标「擦除」动画的 Modifier：
 * - 宽度随 progress 从 [IconSlotWidth] 收缩到 0 —— 这是「擦除」
 * - 内容同时向右平移一个槽位 —— 这是「飞出」，移出去的部分被 [clipToBounds] 裁掉
 * - 内部 IconButton 需要用 `Modifier.requiredWidth(IconSlotWidth)` 保持原始宽度
 *
 * progress 从 0 长到 1 就是反过来的「飞入 + 擦入」，出现动画不用另写一份。
 *
 * @param fade 擦除的同时淡出。跟手渐变（拖动切页签）时必须开：宽度会在中间值停留很久，
 *   只裁不淡会长时间露出半个图标。定时动画（120ms）用默认的 false 就行。
 * @param fly 要不要跟着向右飞。跟手拖动时传 false —— 宽度会停在中间值，
 *   飞出会让图标在中途就被完全裁掉，只剩一段空白。
 */
fun Modifier.eraseToRight(
    progress: Float,
    fade: Boolean = false,
    fly: Boolean = true
): Modifier =
    (if (fade) alpha(progress) else this)
        .clipToBounds()
        .layout { measurable, constraints ->
            val slot = IconSlotWidth.roundToPx()
            val width = (slot * progress).roundToInt().coerceIn(0, slot)
            // 子项始终按原始槽位宽测量（调用方已经用 requiredWidth 顶住了，这里再兜一层）
            val placeable = measurable.measure(
                constraints.copy(minWidth = slot, maxWidth = slot)
            )
            layout(width, placeable.height) {
                val dx = if (fly) ((slot - width) * ERASE_FLY_FRACTION).roundToInt() else 0
                placeable.place(dx, 0)
            }
        }

// ================================================================
//  图标位置移动动画（搜索栏进出时，用户按钮 / 排序按钮一起右滑一个图标位）
// ================================================================

/**
 * 中间按钮在非搜索态时，与右端的间距（让出更多按钮的位置）。
 * 搜索态时归零，让中间按钮移动到最右端。
 *
 * 用户按钮也按中间按钮处理：它贴着复合容器的左边缘，容器宽度同样按这个值收，
 * 所以两颗按钮共用同一个 tween —— 一起往右走一个图标位，不会一前一后。
 */
@Composable
fun rememberMiddleButtonEndPadding(isSearching: Boolean): State<Dp> =
    animateDpAsState(
        targetValue = if (isSearching) 0.dp else IconSlotWidth,
        animationSpec = tween(durationMillis = POSITION_DURATION_MS.toInt()),
        label = "middleButtonEndPadding"
    )

/**
 * 搜索态、复合容器里一颗按钮都不剩时，右侧仍要留的尾部边距。
 * TopAppBar 自带约 4dp，补成 16dp 才能让搜索框右边缘和列表卡片
 * （AppListContainer 默认 horizontal = 16.dp）齐平。
 */
val SearchBarTrailingPadding: Dp = 12.dp

/**
 * 中间按钮 + 更多按钮复合容器的目标宽度。
 *
 * 非搜索态：更多按钮 1 个图标位，加上中间按钮按 [middleVisible] 占的比例。
 * [moreVisible] 是更多按钮自己的权重：菜单里一项都没有时传 0，别再留空槽位。
 * 搜索态：更多按钮被推走，宽度跟着中间按钮收缩，但**不收到 0** —— 没有中间按钮的
 * 页签（设置 / 系统）仍保留 [SearchBarTrailingPadding]，否则搜索框会顶到屏幕边、
 * 和下面的列表不对齐。
 *
 * 连续模式直接取它逐帧跟手，非连续模式拿它当动画目标，两处共用一份公式。
 */
fun actionGroupWidthTarget(
    isSearching: Boolean,
    middleVisible: Float,
    moreVisible: Float = 1f
): Dp {
    val v = middleVisible.coerceIn(0f, 1f)
    val m = moreVisible.coerceIn(0f, 1f)
    return if (isSearching) {
        SearchBarTrailingPadding + (IconSlotWidth - SearchBarTrailingPadding) * v
    } else {
        IconSlotWidth * (m + v)
    }
}

/**
 * 连续模式（跟手切页签）的复合容器宽度。
 *
 * 页签权重照旧逐帧跟手；搜索栏开合这一段改由 [middleButtonEndPadding] 插值 ——
 * 容器少掉的那一个图标位就是用户按钮右移的距离，于是用户按钮和排序按钮
 * 共用同一个 tween，一起往右走、同时到位。
 */
fun actionGroupWidthContinuous(
    middleVisible: Float,
    moreVisible: Float,
    endPadding: Dp
): Dp {
    val normal = actionGroupWidthTarget(false, middleVisible, moreVisible)
    val searching = actionGroupWidthTarget(true, middleVisible, moreVisible)
    val progress = (endPadding / IconSlotWidth).coerceIn(0f, 1f)
    return searching + (normal - searching) * progress
}

/** 复合容器宽度的定时动画版（非连续模式用），时长和 [rememberMiddleButtonEndPadding] 一致。 */
@Composable
fun rememberActionGroupWidth(
    isSearching: Boolean,
    middleVisible: Float = 1f,
    moreVisible: Float = 1f
): State<Dp> =
    animateDpAsState(
        targetValue = actionGroupWidthTarget(isSearching, middleVisible, moreVisible),
        animationSpec = tween(durationMillis = POSITION_DURATION_MS.toInt()),
        label = "actionGroupWidth"
    )

// ================================================================
//  搜索栏展开 / 收缩动画（顶栏所有入口共用）
// ================================================================

/**
 * 搜索栏展开进度：1f 完整展开，0f 完全收起。
 *
 * 开合共用同一个进度 —— 打开时从左往右长出来，关闭时右边缘往左退回去，完全镜像。
 * 必须在顶栏函数体里调用（不要放在会被条件移除的内容里），否则状态随内容销毁，
 * 下次展开就没有从 0 起步的动画了。
 */
@Composable
fun rememberSearchBarProgress(visible: Boolean): State<Float> =
    animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = SearchBarDurationMs),
        label = "searchBarProgress"
    )

/**
 * 水平收缩：容器宽度按 [progress] 从完整宽度收到 0，左边缘钉住、右边缘往左退。
 *
 * 子项按「展开瞬间的完整宽度」锁定测量并放在 (0, 0)，超出的部分被裁掉 ——
 * 所以收缩过程中输入框不会重新布局：文字不挤压、不换行，关闭按钮也不跟着往左跑。
 * 宽度必须锁定：退出搜索时右侧动作区会展开一个图标位，父宽会少 48dp，
 * 若跟着当前父宽测量，占位文案会在收缩途中被挤成两排。
 */
fun Modifier.collapseHorizontally(progress: Float): Modifier =
    clipToBounds().then(CollapseHorizontallyElement(progress))

private data class CollapseHorizontallyElement(
    val progress: Float
) : ModifierNodeElement<CollapseHorizontallyNode>() {
    override fun create() = CollapseHorizontallyNode(progress)

    override fun update(node: CollapseHorizontallyNode) {
        node.progress = progress
        node.invalidateMeasurement()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "collapseHorizontally"
    }
}

private class CollapseHorizontallyNode(
    var progress: Float
) : Modifier.Node(), LayoutModifierNode {
    /** 展开态的可用宽度；收缩期间保持不变，展开态则跟着父宽走（横竖屏 / 切页签都能自适应）。 */
    private var fullWidth = 0

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints
    ): MeasureResult {
        val available = constraints.maxWidth
        if (progress >= 1f) {
            fullWidth = available
        } else if (available > fullWidth) {
            fullWidth = available
        }
        val width = (available * progress.coerceIn(0f, 1f))
            .roundToInt()
            .coerceIn(0, available)
        val placeable = measurable.measure(
            constraints.copy(minWidth = fullWidth, maxWidth = fullWidth)
        )
        return layout(width, placeable.height) {
            placeable.place(0, 0)
        }
    }
}

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

/**
 * 有未完成批次时的呼吸红：在 error 色和默认图标色之间往复。
 *
 * 返回 null 表示不闪，调用方保留原本的 tint / 文字色，不影响其它页面。
 */
@Composable
fun rememberPendingRetryPulse(active: Boolean): Color? {
    if (!active) return null
    val transition = rememberInfiniteTransition(label = "PendingRetryPulse")
    val pulse by transition.animateColor(
        initialValue = MaterialTheme.colorScheme.error,
        targetValue = MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = infiniteRepeatable(
            animation = tween(PENDING_RETRY_PULSE_DURATION_MS, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PendingRetryPulseColor"
    )
    return pulse
}
