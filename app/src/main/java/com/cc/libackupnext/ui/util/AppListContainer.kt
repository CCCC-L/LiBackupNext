package com.cc.libackupnext.ui.util

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun <T> AppListContainer(
    items: List<T>,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    verticalSpacing: Dp = 2.dp,
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
            val isFirst = index == 0
            val isLast = index == items.lastIndex

            val shape = when {
                isFirst && isLast -> RoundedCornerShape(24.dp)
                isFirst -> RoundedCornerShape(
                    topStart = 24.dp, topEnd = 24.dp,
                    bottomStart = 6.dp, bottomEnd = 6.dp
                )
                isLast -> RoundedCornerShape(
                    topStart = 6.dp, topEnd = 6.dp,
                    bottomStart = 24.dp, bottomEnd = 24.dp
                )
                else -> RoundedCornerShape(6.dp)
            }

            itemContent(index, item, shape)
        }
    }
}