package com.cc.libackupnext.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    viewModel: MainViewModel,
    appName: String,
    pageBackground: Color,
    searchBarBackground: Color
) {
    val isSearching by viewModel.isSearching.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()

    // 搜索按钮擦除进度：1 = 完整显示，0 = 完全擦除（120ms）
    val searchButtonErase by animateFloatAsState(
        targetValue = if (isSearching) 0f else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "searchButtonErase"
    )

    // 更多按钮擦除进度：1 = 完整显示，0 = 完全擦除（120ms）
    val moreButtonErase by animateFloatAsState(
        targetValue = if (isSearching) 0f else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "moreButtonErase"
    )

    // 排序按钮的右侧 padding：不搜索时 48dp（排在更多按钮左侧），搜索时 0dp（移动到更多按钮的位置）
    val sortButtonEndPadding by animateDpAsState(
        targetValue = if (isSearching) 0.dp else 48.dp,
        animationSpec = tween(durationMillis = 200),
        label = "sortButtonEndPadding"
    )

    // 排序 + 更多按钮的容器宽度也随之收缩，让搜索栏能够扩展进来
    val sortMoreContainerWidth by animateDpAsState(
        targetValue = if (isSearching) 48.dp else 96.dp,
        animationSpec = tween(durationMillis = 120),
        label = "sortMoreContainerWidth"
    )

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = pageBackground
        ),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                ) {
                    // 标题：始终在布局中，alpha 瞬间切换，永不跳跃
                    Text(
                        text = appName,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.CenterStart)
                            .alpha(if (isSearching) 0f else 1f)
                    )

                    // 搜索框：出现从左往右滑入，消失时文字先消失，然后整个搜索栏再滑出
                    this@Row.AnimatedVisibility(
                        visible = isSearching,
                        enter = fadeIn(animationSpec = tween(200)) + slideInHorizontally(
                            initialOffsetX = { -it / 3 },
                            animationSpec = tween(200)
                        ),
                        exit = fadeOut(animationSpec = tween(200, delayMillis = 100)) +
                                slideOutHorizontally(
                                    targetOffsetX = { -it / 3 },
                                    animationSpec = tween(200, delayMillis = 100)
                                ),
                        modifier = Modifier.height(52.dp)
                    ) {
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { viewModel.updateSearchQuery(it) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth(),
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
                                                text = "搜索应用名称或包名",
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        innerTextField()
                                    }
                                    IconButton(
                                        onClick = { viewModel.toggleSearch() },
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
            // 搜索按钮：原地擦除消失（120ms）
            Box(
                modifier = Modifier
                    .width(48.dp * searchButtonErase)
                    .clipToBounds()
            ) {
                IconButton(
                    onClick = { viewModel.toggleSearch() },
                    modifier = Modifier.requiredWidth(48.dp)
                ) {
                    Icon(Icons.Default.Search, contentDescription = "搜索")
                }
            }

            // 排序 + 更多按钮的复合区域
            Box(
                modifier = Modifier
                    .width(sortMoreContainerWidth)
                    .height(48.dp)
                    .clipToBounds()
            ) {
                // 更多按钮：对齐右端，宽度随 moreButtonErase 收缩（PPT 擦除），120ms
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(48.dp * moreButtonErase)
                        .clipToBounds()
                ) {
                    IconButton(
                        onClick = { viewModel.isMoreMenuExpanded.value = true },
                        modifier = Modifier.requiredWidth(48.dp)
                    ) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多")
                    }
                }

                // 排序按钮：初始在左半区（padding = 48dp），搜索时移动到右半区（padding = 0）
                IconButton(
                    onClick = { viewModel.isSortMenuExpanded.value = true },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = sortButtonEndPadding)
                ) {
                    Icon(Icons.Default.Sort, contentDescription = "排序")
                }
            }

            SortMenu(viewModel)
            MoreMenu(viewModel)
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
            onClick = { viewModel.sortType.value = SortType.NAME; viewModel.applyFiltersAndSort() },
            leadingIcon = { RadioButton(selected = sortType == SortType.NAME, onClick = null) }
        )
        DropdownMenuItem(
            text = { Text("安装时间") },
            onClick = {
                viewModel.sortType.value = SortType.INSTALL_TIME; viewModel.applyFiltersAndSort()
            },
            leadingIcon = {
                RadioButton(selected = sortType == SortType.INSTALL_TIME, onClick = null)
            }
        )
        DropdownMenuItem(
            text = { Text("更新时间") },
            onClick = {
                viewModel.sortType.value = SortType.UPDATE_TIME; viewModel.applyFiltersAndSort()
            },
            leadingIcon = {
                RadioButton(selected = sortType == SortType.UPDATE_TIME, onClick = null)
            }
        )

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )

        DropdownMenuItem(
            text = { Text("倒序") },
            onClick = { viewModel.isReverse.value = !isReverse; viewModel.applyFiltersAndSort() },
            leadingIcon = { Checkbox(checked = isReverse, onCheckedChange = null) }
        )
        DropdownMenuItem(
            text = { Text("显示系统应用") },
            onClick = { viewModel.showSystemApps.value = !showSystem; viewModel.loadApps() },
            leadingIcon = { Checkbox(checked = showSystem, onCheckedChange = null) }
        )
    }
}

@Composable
fun MoreMenu(viewModel: MainViewModel) {
    val expanded by viewModel.isMoreMenuExpanded.collectAsState()

    val isDark = isSystemInDarkTheme()
    val menuColor = if (isDark) Color(0xFF2B2D31) else Color(0xFFF2F3F7)

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { viewModel.isMoreMenuExpanded.value = false },
        shape = RoundedCornerShape(16.dp),
        containerColor = menuColor
    ) {
        DropdownMenuItem(
            text = { Text("重新加载") },
            onClick = {
                viewModel.isMoreMenuExpanded.value = false
                viewModel.loadApps()
            }
        )
    }
}