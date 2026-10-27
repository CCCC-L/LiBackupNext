package com.cc.libackupnext.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.cc.libackupnext.ui.theme.IconSlotWidth
import com.cc.libackupnext.ui.theme.eraseToRight
import com.cc.libackupnext.ui.theme.rememberActionGroupWidth
import com.cc.libackupnext.ui.theme.rememberEraseProgress
import com.cc.libackupnext.ui.theme.rememberMiddleButtonEndPadding
import com.cc.libackupnext.ui.theme.searchBarEnterTransition
import com.cc.libackupnext.ui.theme.searchBarExitTransition
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    viewModel: MainViewModel,
    title: String,
    pageBackground: Color,
    searchBarBackground: Color,
    navigationIcon: (@Composable () -> Unit)? = null,
    middleIcon: ImageVector = Icons.Default.Sort,
    onMiddleClick: () -> Unit = { viewModel.isSortMenuExpanded.value = true },
    showSortMenu: Boolean = true,
    /** 搜索框占位文案，子页面可以改成「搜索备注」。 */
    searchPlaceholder: String = "搜索应用名称或包名",
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
    /** 非空时在「更多」菜单里多一项「设置」。 */
    onOpenSettings: (() -> Unit)? = null,
    /** 非空时在「更多」菜单里多一项「错误日志」。 */
    onOpenErrorLog: (() -> Unit)? = null,
    moreMenuContent: @Composable (onDismiss: () -> Unit) -> Unit = { onDismiss ->
        DropdownMenuItem(
            text = { Text("重新加载") },
            onClick = {
                onDismiss()
                viewModel.loadApps()
            }
        )
        if (onCreateBackup != null) {
            DropdownMenuItem(
                text = { Text("新建应用备份") },
                onClick = {
                    onDismiss()
                    onCreateBackup()
                }
            )
        }
        if (onManageBackup != null) {
            DropdownMenuItem(
                text = { Text("管理应用备份") },
                onClick = {
                    onDismiss()
                    onManageBackup()
                }
            )
        }
        if (onOpenSettings != null) {
            DropdownMenuItem(
                text = { Text("设置") },
                onClick = {
                    onDismiss()
                    onOpenSettings()
                }
            )
        }
        if (onOpenErrorLog != null) {
            DropdownMenuItem(
                text = { Text("错误日志") },
                onClick = {
                    onDismiss()
                    onOpenErrorLog()
                }
            )
        }
    }
) {
    val vmIsSearching by viewModel.isSearching.collectAsState()
    val vmSearchQuery by viewModel.searchQuery.collectAsState()

    // 传了 searchOpen 就用外部的状态（例如应用信息页自己管搜索）
    // 多选模式下不显示搜索框（标题要让位给「已选择 N 项」）
    val isSearching = (searchOpen ?: vmIsSearching) && !selectionMode
    val searchQuery = searchQueryText ?: vmSearchQuery
    val toggleSearch: () -> Unit = { onToggleSearch?.invoke() ?: viewModel.toggleSearch() }
    val updateQuery: (String) -> Unit = { onSearchQueryChange?.invoke(it) ?: viewModel.updateSearchQuery(it) }
    val isMoreMenuExpanded by viewModel.isMoreMenuExpanded.collectAsState()

    // 用户菜单状态
    val users by viewModel.users.collectAsState()
    val isUserMenuExpanded by viewModel.isUserMenuExpanded.collectAsState()
    val currentUserId by viewModel.currentUserId.collectAsState()

    // 搜索框自动聚焦
    val searchFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(isSearching) {
        if (isSearching) {
            // 等 AnimatedVisibility 把输入框布局出来再请求焦点
            delay(120)
            runCatching { searchFocusRequester.requestFocus() }
            keyboardController?.show()
        } else {
            keyboardController?.hide()
        }
    }

    // 每个图标独立使用擦除动画
    val searchButtonErase by rememberEraseProgress(visible = !isSearching)
    val moreButtonErase by rememberEraseProgress(visible = !isSearching)

    // 中间按钮的位置动画 & 复合容器宽度动画
    val middleButtonEndPadding by rememberMiddleButtonEndPadding(isSearching)
    val actionGroupWidth by rememberActionGroupWidth(isSearching)

    val isDark = isSystemInDarkTheme()
    val menuColor = if (isDark) Color(0xFF2B2D31) else Color(0xFFF2F3F7)

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = pageBackground),
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
                    Text(
                        text = if (selectionMode) "已选择 $selectionCount 项" else title,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = titleStyle,
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.CenterStart)
                            .alpha(if (isSearching) 0f else 1f)
                    )

                    this@Row.AnimatedVisibility(
                        visible = isSearching,
                        enter = searchBarEnterTransition(),
                        exit = searchBarExitTransition(),
                        modifier = Modifier
                            .height(52.dp)
                            .align(Alignment.CenterStart)
                    ) {
                        BasicTextField(
                            value = searchQuery,
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
                                            RoundedCornerShape(16.dp)
                                        )
                                        .padding(start = 16.dp, end = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .alpha(if (isSearching) 1f else 0f),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        if (searchQuery.isEmpty()) {
                                            Text(
                                                text = searchPlaceholder,
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        innerTextField()
                                    }
                                    IconButton(
                                        onClick = { toggleSearch() },
                                        modifier = Modifier.size(40.dp)
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
        },
        actions = {
            if (selectionMode) {
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
            // ===== 搜索按钮：使用独立擦除动画 =====
            Box(modifier = Modifier.eraseToRight(searchButtonErase)) {
                IconButton(
                    onClick = { toggleSearch() },
                    modifier = Modifier.requiredWidth(IconSlotWidth)
                ) {
                    Icon(Icons.Default.Search, contentDescription = "搜索")
                }
            }

            // ===== 用户按钮（不参与擦除动画，始终显示） =====
            Box {
                IconButton(
                    onClick = {
                        viewModel.loadUsers()
                        viewModel.isUserMenuExpanded.value = true
                    }
                ) {
                    Icon(Icons.Default.Person, contentDescription = "用户")
                }
                DropdownMenu(
                    expanded = isUserMenuExpanded,
                    onDismissRequest = { viewModel.isUserMenuExpanded.value = false },
                    shape = RoundedCornerShape(16.dp),
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
                                text = { Text(user.name ?: "用户 ${user.id}") },
                                onClick = {
                                    viewModel.currentUserId.value = user.id
                                    viewModel.isUserMenuExpanded.value = false
                                    viewModel.loadApps()
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
                    .clipToBounds()
            ) {
                // 更多按钮：使用独立擦除动画
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .eraseToRight(moreButtonErase)
                ) {
                    IconButton(
                        onClick = { viewModel.isMoreMenuExpanded.value = true },
                        modifier = Modifier.requiredWidth(IconSlotWidth)
                    ) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(
                        expanded = isMoreMenuExpanded,
                        onDismissRequest = { viewModel.isMoreMenuExpanded.value = false },
                        shape = RoundedCornerShape(16.dp),
                        containerColor = menuColor
                    ) {
                        moreMenuContent { viewModel.isMoreMenuExpanded.value = false }
                    }
                }

                // 中间按钮：位置随搜索状态变化
                IconButton(
                    onClick = onMiddleClick,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = middleButtonEndPadding)
                ) {
                    Icon(middleIcon, contentDescription = null)
                }
            }

            if (showSortMenu) SortMenu(viewModel)
            }
        }
    )
}


@Composable
fun SortMenu(viewModel: MainViewModel) {
    val expanded by viewModel.isSortMenuExpanded.collectAsState()
    val sortType by viewModel.sortType.collectAsState()
    val isReverse by viewModel.isReverse.collectAsState()
    val showSystem by viewModel.showSystemApps.collectAsState()

    val isDark = isSystemInDarkTheme()
    val menuColor = if (isDark) Color(0xFF2B2D31) else Color(0xFFF2F3F7)

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { viewModel.isSortMenuExpanded.value = false },
        shape = RoundedCornerShape(16.dp),
        containerColor = menuColor
    ) {
        DropdownMenuItem(
            text = { Text("应用名称") },
            onClick = {
                viewModel.sortType.value = SortType.NAME
                viewModel.applyFiltersAndSort()  // 只本地重排，不请求
            },
            leadingIcon = { RadioButton(selected = sortType == SortType.NAME, onClick = null) }
        )
        DropdownMenuItem(
            text = { Text("安装时间") },
            onClick = {
                viewModel.sortType.value = SortType.INSTALL_TIME
                viewModel.applyFiltersAndSort()
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

        DropdownMenuItem(
            text = { Text("倒序") },
            onClick = {
                viewModel.isReverse.value = !isReverse
                viewModel.applyFiltersAndSort()
            },
            leadingIcon = { Checkbox(checked = isReverse, onCheckedChange = null) }
        )
        DropdownMenuItem(
            text = { Text("显示系统应用") },
            onClick = {
                viewModel.showSystemApps.value = !showSystem
                viewModel.applyFiltersAndSort()  // 只本地过滤，不请求
            },
            leadingIcon = { Checkbox(checked = showSystem, onCheckedChange = null) }
        )
    }
}