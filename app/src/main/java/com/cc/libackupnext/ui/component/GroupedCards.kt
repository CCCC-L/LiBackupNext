package com.cc.libackupnext.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 「分组卡片」这一套规则的唯一出口。
 *
 * 设置页、关于页、开源声明页、apptab 列表、批量页都用同一套形状：一组的第 i 行圆角由
 * [groupedItemShape] 按位置算，行与行之间只隔 [GroupVerticalSpacing]，没有分割线 ——
 * 一组看起来就是一张被切成几段的卡片。
 *
 * 这里有四个入口，选哪个只看调用方是什么容器：
 * - [AppListContainer]：数据驱动的 LazyColumn，自己管 state / padding / key
 * - [cardGroup]：已经在 LazyColumn 里，再往里追加一组（开源声明页）
 * - [CardGroup]：普通 Column 里的一组（设置页、关于页）
 * - [SettingsCard]：组里的一行
 *
 * 形状规则只有 [groupedItemShape] 一份，四个入口都走它，不要各写一套圆角。
 */

/** 一组卡片里第 [index] 项该用的圆角（[count] 是这一组的行数）。 */
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

@Composable
fun SectionTitle(text: String) {
    // 撑满宽度：关于页的父 Column 是水平居中的，不撑满标题会跟着居中
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 12.dp, bottom = 6.dp)
    )
}

/**
 * 一组卡片：组标题 + 若干行。
 *
 * 行用 lambda 传进来（而不是直接写在 Column 里），是为了让每一行拿到自己该用的
 * [Shape] —— 圆角取决于它在组内的位置，只有外层知道一共有几行。
 *
 * @param title 组标题；传 null 就不带标题（关于页的版本卡这种）。
 */
@Composable
fun CardGroup(
    title: String?,
    vararg rows: @Composable (Shape) -> Unit
) {
    if (title != null) SectionTitle(title)
    rows.forEachIndexed { index, row ->
        if (index > 0) Spacer(modifier = Modifier.height(GroupVerticalSpacing))
        row(groupedItemShape(index, rows.size))
    }
}

/**
 * [CardGroup] 的 LazyColumn 版：长列表（开源软件声明）用它，圆角 / 间距规则一模一样，
 * 只是每行作为独立的 lazy item 提交，不一次性组合。
 */
fun <T> LazyListScope.cardGroup(
    title: String?,
    items: List<T>,
    key: ((item: T) -> Any)? = null,
    row: @Composable (item: T, shape: Shape) -> Unit
) {
    if (title != null) {
        item(key = "header-$title") { SectionTitle(title) }
    }
    itemsIndexed(
        items = items,
        key = if (key == null) null else { _, item -> key(item) }
    ) { index, item ->
        row(item, groupedItemShape(index, items.size))
    }
}

/**
 * 分组卡片里的一行：标题 + 说明，可带一行小字提示和右侧控件（开关 / 按钮 / 图标）。
 *
 * 形状由外层的 [CardGroup] 给，这里只管内容和内边距；标题 / 说明的字重沿用设置页的写法
 * （bodyMedium Medium + bodySmall），比列表卡片的 titleMedium 轻一档。
 */
@Composable
fun SettingsCard(
    shape: Shape,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    /** 说明下面的小字提示（「推荐关闭」这类），不传就没有这一行。 */
    hint: String? = null,
    /** 说明最多显示几行；仓库地址这种超长路径限制成一行，别把卡片撑高。 */
    subtitleMaxLines: Int = Int.MAX_VALUE,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    hintColor: Color = MaterialTheme.colorScheme.outline,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val clickModifier = when {
        onLongClick != null -> Modifier.combinedClickable(
            onClick = { onClick?.invoke() },
            onLongClick = onLongClick
        )
        onClick != null -> Modifier.clickable(onClick = onClick)
        else -> Modifier
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(clickModifier),
        shape = shape,
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = subtitleColor,
                    maxLines = subtitleMaxLines,
                    overflow = TextOverflow.Ellipsis
                )
                if (hint != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.labelSmall,
                        color = hintColor
                    )
                }
            }
            if (trailing != null) {
                Spacer(modifier = Modifier.width(12.dp))
                trailing()
            }
        }
    }
}
