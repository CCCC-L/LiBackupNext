package com.cc.libackupnext.ui.util

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 一组卡片里第 [index] 项该用的圆角（[count] 是这一组的行数）：
 * 只有一行时四角都大；首行上大下小、末行上小下大、中间全小。
 *
 * 行与行之间只隔 [GroupVerticalSpacing]，拼起来像一张被切成几段的卡片。
 * 设置页的分组卡片也用这一份规则，两处别各写一套。
 */
@Composable
fun groupedItemShape(index: Int, count: Int): Shape {
    val shapes = MaterialTheme.shapes
    val largeCorner = shapes.extraLarge.topStart
    val smallCorner = shapes.extraSmall.topStart
    val isFirst = index == 0
    val isLast = index == count - 1
    return when {
        isFirst && isLast -> shapes.extraLarge
        isFirst -> RoundedCornerShape(
            topStart = largeCorner, topEnd = largeCorner,
            bottomStart = smallCorner, bottomEnd = smallCorner
        )
        isLast -> RoundedCornerShape(
            topStart = smallCorner, topEnd = smallCorner,
            bottomStart = largeCorner, bottomEnd = largeCorner
        )
        else -> shapes.extraSmall
    }
}

/** 组内相邻两行之间的缝：就是页面底色露出来的那一条。 */
val GroupVerticalSpacing: Dp = 2.dp

@Composable
fun <T> AppListContainer(
    items: List<T>,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    verticalSpacing: Dp = GroupVerticalSpacing,
    header: (@Composable () -> Unit)? = null,
    key: ((index: Int, item: T) -> Any)? = null,
    itemContent: @Composable (index: Int, item: T, shape: Shape) -> Unit
) {
    LazyColumn(
        modifier = modifier,
        state = state,
        verticalArrangement = Arrangement.spacedBy(verticalSpacing),
        contentPadding = contentPadding
    ) {
        if (header != null) {
            item { header() }
        }
        itemsIndexed(items, key = key) { index, item ->
            itemContent(index, item, groupedItemShape(index, items.size))
        }
    }
}
