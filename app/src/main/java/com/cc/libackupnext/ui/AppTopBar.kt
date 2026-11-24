package com.cc.libackupnext.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cc.libackupnext.ui.component.CheckboxMenuItem
import com.cc.libackupnext.ui.theme.IconSlotWidth
import com.cc.libackupnext.ui.theme.SearchButtonHideMs
import com.cc.libackupnext.ui.theme.actionGroupWidthContinuous
import com.cc.libackupnext.ui.theme.collapseHorizontally
import com.cc.libackupnext.ui.theme.eraseToRight
import com.cc.libackupnext.ui.theme.rememberActionGroupWidth
import com.cc.libackupnext.ui.theme.rememberEraseProgress
import com.cc.libackupnext.ui.theme.rememberMiddleButtonEndPadding
import com.cc.libackupnext.ui.theme.rememberPendingRetryPulse
import com.cc.libackupnext.ui.theme.rememberSearchBarProgress
import kotlinx.coroutines.android.awaitFrame
import kotlin.math.floor

@Composable
fun AppTopBar(
    /** tab 页传 Activity 作用域的 ViewModel；文件夹选择弹窗传 null，自己接管搜索和动作区。 */
    viewModel: MainViewModel? = null,
    title: String,
    pageBackground: Color,
    searchBarBackground: Color,
    navigationIcon: (@Composable () -> Unit)? = null,
    middleIcon: ImageVector = Icons.AutoMirrored.Filled.Sort,
    onMiddleClick: () -> Unit = {
        viewModel?.let { it.isSortMenuExpanded.value = true }
    },
    showSortMenu: Boolean = true,
    /** 排序菜单最后一项放什么：列表页是「显示系统应用」，批量页换成别的。 */
    sortMenuExtra: SortMenuExtra = SortMenuExtra.SYSTEM_APPS,
    /** 是否显示四种排序方式：文件夹页只有「倒序」，不需要这些只针对应用的排序。 */
    showSortTypes: Boolean = true,
    /** 帮助按钮 */
    showMiddleButton: Boolean = showSortMenu,
    /** 搜索框占位文案，子页面可以改成「搜索备注」。 */
    searchPlaceholder: String = "搜索应用名称或包名",
    /** 是否显示右上角的用户切换按钮（应用 / 文件夹这种「属于某个用户」的目标才显示）。 */
    showUserButton: Boolean = true,
    /** 是否显示搜索按钮（设置页没有可搜的东西）。 */
    showSearch: Boolean = true,
    /**
     * 连续模式：搜索按钮的显示权重（0..1）。
     *
     * 滑动切页签时传它 —— 宽度逐帧跟着手指变，而不是等状态翻转后再补一段 120ms 动画。
     * 传 null 就退回 [showSearch] + 擦除动画。
     */
    searchWeight: (() -> Float)? = null,
    /** 连续模式：用户按钮的权重，含义同 [searchWeight]。 */
    userWeight: (() -> Float)? = null,
    /** 连续模式：中间按钮的权重，含义同 [searchWeight]。 */
    middleWeight: (() -> Float)? = null,
    /**
     * 连续模式：更多按钮的权重，含义同 [searchWeight]。
     *
     * 设置页没有更多菜单，滑到那一页时这颗按钮要和用户 / 中间按钮一样跟着手指收，
     * 而不是等 tab 翻牌后再补一段淡出。
     */
    moreWeight: (() -> Float)? = null,
    /**
     * 连续模式：每页一个标题，按 [pagePosition] 在相邻两页之间交叉淡化。
     */
    pageTitles: List<String>? = null,
    /** 连续模式：页面位置（0 = 第一页，1 = 第二页……）。拖动 / 惯性 / 程序化滚动都适用。 */
    pagePosition: (() -> Float)? = null,
    /** 外部接管搜索状态：传了就用自己的，不再读写 ViewModel 里的全局搜索状态。 */
    searchOpen: Boolean? = null,
    searchQueryText: String? = null,
    onToggleSearch: (() -> Unit)? = null,
    onSearchQueryChange: ((String) -> Unit)? = null,
    /** 标题字号，子页面可以传小一点的样式。 */
    titleStyle: TextStyle = MaterialTheme.typography.titleLarge,
    /** 多选模式：标题换成「已选择 N 项」，右侧按钮换成全选 / 反选。 */
    selectionMode: Boolean = false,
    /** 多选里选了多少项。 */
    selectionCount: Int = 0,
    /** 当前可见的应用是不是已经全选。 */
    allSelected: Boolean = false,
    onSelectAll: (() -> Unit)? = null,
    onInvert: (() -> Unit)? = null,
    /** 非空时在「更多」菜单里多一项「新建应用备份」。 */
    onCreateBackup: (() -> Unit)? = null,
    /** 非空时在「更多」菜单里多一项「管理应用备份」。 */
    onManageBackup: (() -> Unit)? = null,
    /** 这两个菜单项的文案，文件页会换成「新建/管理文件备份」。 */
    createBackupLabel: String = "新建应用备份",
    manageBackupLabel: String = "管理应用备份",
    /** 非空时在「更多」菜单里多一项「设置」。 */
    onOpenSettings: (() -> Unit)? = null,
    /** 「重新加载」；传 null 就不显示这一项（设置页）。 */
    onReload: (() -> Unit)? = null,
    /** 非空时在「更多」菜单末尾加「继续未完成（N）」；只有 tab 页传。 */
    pendingRetryCount: Int = 0,
    onRetryPending: (() -> Unit)? = null,
    /**
     * 自定义「更多」菜单内容；传 null 就用默认菜单（按上面几个入口自动拼）。
     *
     * 传了它就一定显示更多按钮 —— 菜单里有什么由调用方决定，顶栏不推断。
     */
    moreMenuContent: (@Composable (onDismiss: () -> Unit) -> Unit)? = null,
    /**
     * 非空时替换「搜索按钮之后」的动作区（文件夹选择弹窗用它平铺新建 / 刷新）。
     * 搜索按钮始终由顶栏统一渲染，展开搜索栏时在自己槽位内擦除让位。
     */
    customActions: (@Composable RowScope.() -> Unit)? = null,
    /** Dialog 是独立窗口且外层已做过 statusBarsPadding，传零 inset 免得顶栏再下沉一次。 */
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets
) {
    // ViewModel 可空：文件夹选择弹窗不碰主页面的全局状态，只靠外部接管的搜索参数驱动。
    val vmIsSearching = if (viewModel != null) {
        viewModel.isSearching.collectAsState().value
    } else {
        false
    }
    val vmSearchQuery = if (viewModel != null) {
        viewModel.searchQuery.collectAsState().value
    } else {
        ""
    }

    // 传了 searchOpen 就用外部的状态（例如应用信息页自己管搜索）
    // 多选模式下不显示搜索框（标题要让位给「已选择 N 项」）
    val isSearching = showSearch && (searchOpen ?: vmIsSearching) && !selectionMode
    val searchQuery = searchQueryText ?: vmSearchQuery
    val toggleSearch: () -> Unit = {
        onToggleSearch?.invoke() ?: viewModel?.toggleSearch()
    }
    val updateQuery: (String) -> Unit = { text ->
        onSearchQueryChange?.invoke(text) ?: viewModel?.updateSearchQuery(text)
    }

    // 搜索栏开合共用的进度：状态留在顶栏这一层，内容收完被移除也不会丢动画。
    val searchBarProgress by rememberSearchBarProgress(isSearching)

    // 关闭那一帧 ViewModel / 弹窗会清空 query，留一份最近的输入给收缩动画继续显示，
    // 否则收到一半文字会突然跳回占位文案。下次打开时 searchQuery 为空，自然回到占位符。
    var closingQuery by remember { mutableStateOf("") }
    LaunchedEffect(isSearching, searchQuery) {
        if (isSearching) closingQuery = searchQuery
    }
    val displayQuery = if (isSearching) searchQuery else closingQuery

    val isMoreMenuExpanded = if (viewModel != null) {
        viewModel.isMoreMenuExpanded.collectAsState().value
    } else {
        false
    }

    // 用户菜单状态
    val users = if (viewModel != null) {
        viewModel.users.collectAsState().value
    } else {
        emptyList()
    }
    val isUserMenuExpanded = if (viewModel != null) {
        viewModel.isUserMenuExpanded.collectAsState().value
    } else {
        false
    }
    val currentUserId = if (viewModel != null) {
        viewModel.currentUserId.collectAsState().value
    } else {
        0
    }

    // 搜索框自动聚焦
    val searchFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(isSearching) {
        if (isSearching) {
            // 展开动画起步就聚焦并弹键盘；等一帧只是让刚进组的输入框完成挂载，
            awaitFrame()
            runCatching { searchFocusRequester.requestFocus() }
            keyboardController?.show()
        } else {
            keyboardController?.hide()
        }
    }

    // 搜索按钮一律在自己槽位内擦除，跟随更多按钮一起 750ms
    val searchOpenErase by rememberEraseProgress(
        visible = !isSearching,
        durationMs = SearchButtonHideMs
    )
    val searchErase = searchWeight?.let { weight -> weight() * searchOpenErase }

    val userAnimated by rememberEraseProgress(visible = showUserButton)
    val userErase = userWeight?.invoke() ?: userAnimated

    val middleAnimated by rememberEraseProgress(visible = showMiddleButton)
    val middleWeightValue = middleWeight?.invoke()
    val middleErase = middleWeightValue ?: middleAnimated

    // 更多按钮：菜单里一项都没有（设置页没有可刷新 / 可新建的内容）就不显示 ——
    // 点开只有空菜单。宽度和透明度一起收，动作区不留下一个空槽位。
    // 有未完成批次时 onRetryPending 非空，按钮会重新出现并闪红。
    // 自定义 moreMenuContent 的页面（详情页 / 批量页）那几个入口一个都不传，
    // 但菜单是实打实有内容的，所以它本身也算「有菜单」。
    val hasMoreMenu = moreMenuContent != null || onReload != null ||
            onCreateBackup != null || onManageBackup != null ||
            onOpenSettings != null || onRetryPending != null
    val moreAnimated by rememberEraseProgress(visible = hasMoreMenu)
    // 连续模式：更多按钮也走权重（设置页没有它），和用户 / 中间按钮同一套擦除。
    // 未完成批次是离散状态，单独补一段 200ms —— 否则在设置页把它清掉时按钮会瞬间消失。
    val morePendingAnimated by rememberEraseProgress(visible = onRetryPending != null)
    val moreErase = maxOf(moreWeight?.invoke() ?: moreAnimated, morePendingAnimated)

    // 中间按钮的位置动画 & 复合容器宽度动画
    val middleButtonEndPadding by rememberMiddleButtonEndPadding(isSearching)
    // 搜索态更多按钮被推走，只剩中间按钮；中间按钮本身也可能被擦除（设置 / 系统页
    // 权重为 0），这时仍要留一段尾部边距，否则搜索框会顶到屏幕边、和列表不对齐。
    // 连续模式直接用跟手权重，非连续模式按 showMiddleButton 走定时动画。
    val middleVisible = (middleWeightValue ?: (if (showMiddleButton) 1f else 0f))
        .coerceIn(0f, 1f)
    val actionGroupWidthAnimated by rememberActionGroupWidth(
        isSearching,
        middleVisible,
        moreVisible = if (hasMoreMenu) 1f else 0f
    )
    val actionGroupWidth = if (middleWeightValue != null) {
        // 连续模式逐帧跟手；搜索栏开合这一段跟着 middleButtonEndPadding 走，
        // 用户按钮（贴着容器左边缘）和排序按钮一起右移一个图标位
        actionGroupWidthContinuous(middleVisible, moreErase, middleButtonEndPadding)
    } else {
        actionGroupWidthAnimated
    }

    val menuColor = MaterialTheme.colorScheme.surfaceContainerHigh

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = pageBackground),
        windowInsets = windowInsets,
        navigationIcon = {
            if (navigationIcon != null) navigationIcon()
        },
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 固定高度 64dp，保证搜索框进出时标题不跳动
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(64.dp)
                ) {
                    // 换页签时标题用「宽度展开」切换：宽度和透明度取同一个比例，
                    // 和底栏的文字一样 —— 旧的收完、新的再从 0 宽长出来，两段不会叠在一起。
                    // 标题随搜索栏的展开 / 收缩反向淡入淡出，收起时不会突然露出整行文字
                    val baseAlpha = if (isSearching) 0f else 1f - searchBarProgress
                    val titles = pageTitles
                    val position = pagePosition
                    if (titles != null && position != null && titles.isNotEmpty()) {
                        // 连续模式：只看夹住当前位置的两页。旧的收到一半（50%）、
                        // 新的从 35% 就开始长，两段不叠在一起，中间也不会空一帧。
                        // 位置在这里读 —— 放到调用方按位置算标题会让整页每帧重组。
                        val p = position()
                        val lower = floor(p).toInt().coerceIn(0, titles.lastIndex)
                        val upper = (lower + 1).coerceAtMost(titles.lastIndex)
                        val progress = (p - lower).coerceIn(0f, 1f)
                        TitleText(
                            text = titles[lower],
                            progress = ((0.5f - progress) / 0.5f).coerceIn(0f, 1f),
                            baseAlpha = baseAlpha,
                            style = titleStyle
                        )
                        TitleText(
                            text = titles[upper],
                            progress = ((progress - 0.35f) / 0.65f).coerceIn(0f, 1f),
                            baseAlpha = baseAlpha,
                            style = titleStyle
                        )
                    } else {
                        TitleText(
                            text = if (selectionMode) "已选择 $selectionCount 项" else title,
                            progress = 1f,
                            baseAlpha = baseAlpha,
                            style = titleStyle
                        )
                    }

                    // 收起过程中内容要留着被一起擦掉，所以等进度真的归零才移除。
                    if (isSearching || searchBarProgress > 0f) {
                        Box(
                            modifier = Modifier
                                .height(52.dp)
                                .align(Alignment.CenterStart)
                                .collapseHorizontally(searchBarProgress)
                        ) {
                            BasicTextField(
                                value = displayQuery,
                                onValueChange = { updateQuery(it) },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(searchFocusRequester),
                                decorationBox = { innerTextField ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(52.dp)
                                            .background(
                                                searchBarBackground,
                                                MaterialTheme.shapes.large
                                            )
                                            .padding(start = 16.dp, end = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier.weight(1f),
                                            contentAlignment = Alignment.CenterStart
                                        ) {
                                            if (displayQuery.isEmpty()) {
                                                Text(
                                                    text = searchPlaceholder,
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    // 收缩期间父宽会变，占位文案别被挤成两排
                                                    maxLines = 1,
                                                    softWrap = false,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            innerTextField()
                                        }
                                        Box(
                                            modifier = Modifier
                                                .size(40.dp)
                                                .clickable(
                                                    interactionSource = remember {
                                                        MutableInteractionSource()
                                                    },
                                                    indication = null,
                                                    onClick = { toggleSearch() }
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = "关闭搜索",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        },
        actions = {
            // 搜索按钮统一由顶栏渲染（包括自定义动作区的弹窗），展开搜索栏时
            // 在自己槽位内擦除让位；多选模式让位给全选 / 反选。
            if (!selectionMode) {
                SearchActionButton(
                    showSearch = showSearch,
                    progress = searchBarProgress,
                    searchWeight = searchWeight,
                    searchErase = searchErase,
                    onClick = { toggleSearch() }
                )
            }
            if (customActions != null) {
                // 文件夹选择弹窗：搜索按钮之后只放新建 / 刷新，不带用户 / 排序 / 更多菜单
                customActions.invoke(this)
            } else if (selectionMode) {
                // 全选 / 取消全选
                IconButton(onClick = { onSelectAll?.invoke() }) {
                    Icon(
                        imageVector = if (allSelected) Icons.Default.Deselect else Icons.Default.SelectAll,
                        contentDescription = if (allSelected) "取消全选" else "全选"
                    )
                }
                // 反选
                IconButton(onClick = { onInvert?.invoke() }) {
                    Icon(
                        imageVector = Icons.Default.SwapVert,
                        contentDescription = "反选"
                    )
                }
            } else {
            // ===== 用户按钮：和搜索按钮同一套擦除动画 =====
            Box(
                modifier = Modifier.eraseToRight(
                    userErase,
                    fade = userWeight != null,
                    fly = userWeight == null
                )
            ) {
                IconButton(
                    onClick = {
                        viewModel?.let { vm ->
                            vm.loadUsers()
                            vm.isUserMenuExpanded.value = true
                        }
                    },
                    modifier = Modifier.requiredWidth(IconSlotWidth)
                ) {
                    Icon(Icons.Default.Person, contentDescription = "用户")
                }
                DropdownMenu(
                    expanded = isUserMenuExpanded,
                    onDismissRequest = {
                        viewModel?.let { it.isUserMenuExpanded.value = false }
                    },
                    shape = MaterialTheme.shapes.large,
                    containerColor = menuColor
                ) {
                    if (users.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("暂无用户") },
                            onClick = { }
                        )
                    } else {
                        users.forEach { user ->
                            DropdownMenuItem(
                                text = { Text(user.name) },
                                // 只改这一个值：谁需要跟着变，谁自己盯着它（见 MainViewModel.init）
                                onClick = {
                                    viewModel?.let { vm ->
                                        vm.isUserMenuExpanded.value = false
                                        vm.currentUserId.value = user.id
                                    }
                                },
                                leadingIcon = {
                                    RadioButton(
                                        selected = currentUserId == user.id,
                                        onClick = null
                                    )
                                }
                            )
                        }
                    }
                }
            }

            // ===== 中间按钮 + 更多按钮复合区域 =====
            Box(
                modifier = Modifier
                    .width(actionGroupWidth)
                    .height(48.dp)
            ) {
                // 更多按钮：搜索弹出时和搜索按钮一样在槽内擦除消失（不再向右推出）
                Box(
                    modifier = Modifier.align(Alignment.CenterEnd)
                ) {
                    IconButton(
                        onClick = {
                            viewModel?.let { it.isMoreMenuExpanded.value = true }
                        },
                        // 菜单空 / 搜索弹出时都整体擦除：宽度收到 0、内容淡出，和搜索按钮同一套。
                        // 两处权重相乘：任一为 0 就收起。
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .eraseToRight(moreErase * searchOpenErase, fade = true, fly = false)
                    ) {
                        // 有未完成批次时呼吸闪红，没完成时保持原本图标色
                        val pulse = rememberPendingRetryPulse(onRetryPending != null)
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "更多",
                            tint = pulse ?: LocalContentColor.current
                        )
                    }
                    DropdownMenu(
                        expanded = isMoreMenuExpanded,
                        onDismissRequest = {
                            viewModel?.let { it.isMoreMenuExpanded.value = false }
                        },
                        shape = MaterialTheme.shapes.large,
                        containerColor = menuColor
                    ) {
                        val dismissMoreMenu: () -> Unit = {
                            viewModel?.let { it.isMoreMenuExpanded.value = false }
                        }
                        if (moreMenuContent != null) {
                            moreMenuContent.invoke(dismissMoreMenu)
                        } else {
                            DefaultMoreMenuContent(
                                onReload = onReload,
                                onCreateBackup = onCreateBackup,
                                onManageBackup = onManageBackup,
                                onOpenSettings = onOpenSettings,
                                onRetryPending = onRetryPending,
                                pendingRetryCount = pendingRetryCount,
                                createBackupLabel = createBackupLabel,
                                manageBackupLabel = manageBackupLabel,
                                onDismiss = dismissMoreMenu
                            )
                        }
                    }
                }

                // 中间按钮：位置随搜索状态变化；设置页没有它，擦除收起而不是硬切
                // 让位的间距跟着更多按钮一起收：更多按钮没了就不用给它留位置
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = middleButtonEndPadding * moreErase)
                        .eraseToRight(
                            middleErase,
                            fade = middleWeight != null,
                            fly = middleWeight == null
                        )
                ) {
                    IconButton(
                        onClick = onMiddleClick,
                        modifier = Modifier.requiredWidth(IconSlotWidth)
                    ) {
                        Icon(middleIcon, contentDescription = null)
                    }
                }
            }

            if (showSortMenu) viewModel?.let { SortMenu(it, sortMenuExtra, showSortTypes) }
            }
        }
    )
}

/**
 * 标题的一层：宽度和透明度取同一个比例 —— 是「展开 / 收起」，不是整体淡入。
 *
 * 两份文字交替长 / 收就成了交叉淡化：旧的收到 0 宽时新的才长到一半，中间不会空一帧。
 * 超出宽度的部分被 [clipToBounds] 裁掉，所以收缩时字不会被挤扁。
 */
@Composable
private fun BoxScope.TitleText(
    text: String,
    /** 展开比例：0 = 完全收起，1 = 完整显示。 */
    progress: Float,
    /** 搜索栏开合带来的整体淡出。 */
    baseAlpha: Float,
    style: TextStyle
) {
    val p = progress.coerceIn(0f, 1f)
    Text(
        text = text,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = style,
        modifier = Modifier
            .fillMaxWidth(p)
            .clipToBounds()
            .align(Alignment.CenterStart)
            .alpha(baseAlpha * p)
    )
}

/**
 * 「更多」菜单的默认内容：按传进来的入口自动拼。
 *
 * 自定义 [AppTopBar] 的 `moreMenuContent` 的页面（详情页 / 批量页）不走这里，
 * 但那些页面同样算「有菜单」，更多按钮照常显示。
 */
@Composable
private fun DefaultMoreMenuContent(
    onReload: (() -> Unit)?,
    onCreateBackup: (() -> Unit)?,
    onManageBackup: (() -> Unit)?,
    onOpenSettings: (() -> Unit)?,
    onRetryPending: (() -> Unit)?,
    pendingRetryCount: Int,
    createBackupLabel: String,
    manageBackupLabel: String,
    onDismiss: () -> Unit
) {
    // 传了才显示：设置页这种没有可刷新内容的页面不传
    if (onReload != null) {
        DropdownMenuItem(
            text = { Text("重新加载") },
            onClick = {
                onDismiss()
                onReload.invoke()
            }
        )
    }
    if (onCreateBackup != null) {
        DropdownMenuItem(
            text = { Text(createBackupLabel) },
            onClick = {
                onDismiss()
                onCreateBackup.invoke()
            }
        )
    }
    if (onManageBackup != null) {
        DropdownMenuItem(
            text = { Text(manageBackupLabel) },
            onClick = {
                onDismiss()
                onManageBackup.invoke()
            }
        )
    }
    if (onOpenSettings != null) {
        DropdownMenuItem(
            text = { Text("设置") },
            onClick = {
                onDismiss()
                onOpenSettings.invoke()
            }
        )
    }
    // 有未完成批次才出现，放菜单最末尾；和「更多」按钮一起闪红提示
    if (onRetryPending != null) {
        DropdownMenuItem(
            text = {
                val pulse = rememberPendingRetryPulse(true)
                Text(
                    "继续未完成（$pendingRetryCount）",
                    color = pulse ?: Color.Unspecified
                )
            },
            onClick = {
                onDismiss()
                onRetryPending.invoke()
            }
        )
    }
}

/**
 * 搜索按钮：所有页面统一在自己 48dp 槽位内被裁掉、宽度收到 0，右边缘始终贴着
 * 下一个动作按钮，不会飞过右侧按钮再消失在屏幕边界。主 tab 页和文件夹选择弹窗
 * 共用这一颗，进度都来自同一个 [progress]（展开 / 收缩完全镜像）。
 *
 * 切页签跟手的连续模式例外：改由外部权重驱动同一份擦除，才能逐帧跟手。
 */
@Composable
private fun SearchActionButton(
    showSearch: Boolean,
    progress: Float,
    searchWeight: (() -> Float)?,
    searchErase: Float?,
    onClick: () -> Unit
) {
    val erase = if (searchWeight != null) {
        // 连续模式：外部权重里已经把搜索态算成 0，逐帧跟手不额外补动画
        searchErase ?: 0f
    } else {
        // 普通模式：搜索栏展开到哪，按钮槽位就收到哪
        (if (showSearch) 1f else 0f) * (1f - progress)
    }
    Box(modifier = Modifier.eraseToRight(erase, fade = true, fly = false)) {
        IconButton(onClick = onClick, modifier = Modifier.requiredWidth(IconSlotWidth)) {
            Icon(Icons.Default.Search, contentDescription = "搜索")
        }
    }
}


/** 排序菜单最后一项放什么。三个页面能做的事不一样，用不着都显示。 */
enum class SortMenuExtra {
    /** 应用列表页：「显示系统应用」开关（持久化）。 */
    SYSTEM_APPS,

    /** 应用批量页：「置顶不同版本」—— 备份时的版本和现在装的不一致，排最前面。 */
    PIN_DIFFERENT_VERSIONS,

    /** 文件夹批量页：没有这一项（既没有系统应用可过滤，也没有版本可比）。系统数据页不显示排序按钮。 */
    NONE
}


@Composable
fun SortMenu(
    viewModel: MainViewModel,
    extra: SortMenuExtra = SortMenuExtra.SYSTEM_APPS,
    /** 传 false 时菜单只剩「倒序」（文件夹页没有应用那四种排序方式）。 */
    showSortTypes: Boolean = true
) {
    val expanded by viewModel.isSortMenuExpanded.collectAsState()
    val sortType by viewModel.sortType.collectAsState()
    val isReverse by viewModel.isReverse.collectAsState()
    val showSystem by viewModel.showSystemApps.collectAsState()
    val pinDifferent by viewModel.pinDifferentVersions.collectAsState()

    val menuColor = MaterialTheme.colorScheme.surfaceContainerHigh

    // 和「更多」菜单一致：选完任何一项就收起，不用再点一下菜单外面
    val closeMenu = { viewModel.isSortMenuExpanded.value = false }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = closeMenu,
        shape = MaterialTheme.shapes.large,
        containerColor = menuColor
    ) {
        if (showSortTypes) {
            DropdownMenuItem(
                text = { Text("应用名称") },
                onClick = {
                    viewModel.sortType.value = SortType.NAME
                    viewModel.applyFiltersAndSort()  // 只本地重排，不请求
                    closeMenu()
                },
                leadingIcon = { RadioButton(selected = sortType == SortType.NAME, onClick = null) }
            )
            DropdownMenuItem(
                text = { Text("安装时间") },
                onClick = {
                    viewModel.sortType.value = SortType.INSTALL_TIME
                    viewModel.applyFiltersAndSort()
                    closeMenu()
                },
                leadingIcon = {
                    RadioButton(selected = sortType == SortType.INSTALL_TIME, onClick = null)
                }
            )
            DropdownMenuItem(
                text = { Text("更新时间") },
                onClick = {
                    viewModel.sortType.value = SortType.UPDATE_TIME
                    viewModel.applyFiltersAndSort()
                    closeMenu()
                },
                leadingIcon = {
                    RadioButton(selected = sortType == SortType.UPDATE_TIME, onClick = null)
                }
            )

            DropdownMenuItem(
                text = { Text("备份时间") },
                onClick = {
                    viewModel.sortType.value = SortType.BACKUP_TIME
                    viewModel.applyFiltersAndSort()
                    closeMenu()
                },
                leadingIcon = {
                    RadioButton(selected = sortType == SortType.BACKUP_TIME, onClick = null)
                }
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
        }

        CheckboxMenuItem(
            text = "倒序",
            checked = isReverse,
            onClick = {
                viewModel.isReverse.value = !isReverse
                viewModel.applyFiltersAndSort()
                closeMenu()
            }
        )
        when (extra) {
            SortMenuExtra.SYSTEM_APPS -> CheckboxMenuItem(
                text = "显示系统应用",
                checked = showSystem,
                onClick = {
                    viewModel.showSystemApps.value = !showSystem
                    viewModel.applyFiltersAndSort()  // 只本地过滤，不请求
                    closeMenu()
                }
            )

            // 批量页没有系统应用可过滤（列表本来就只有仓库里的那些），换成这个更有用。
            // 重排由批量页自己做（visibleApps），这里只翻开关。
            SortMenuExtra.PIN_DIFFERENT_VERSIONS -> CheckboxMenuItem(
                text = "置顶不同版本",
                checked = pinDifferent,
                onClick = {
                    viewModel.pinDifferentVersions.value = !pinDifferent
                    closeMenu()
                }
            )

            SortMenuExtra.NONE -> Unit
        }
    }
}