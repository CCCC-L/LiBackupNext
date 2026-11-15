package com.cc.libackupnext.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.unit.dp
import com.cc.libackupnext.ui.util.GroupVerticalSpacing
import com.cc.libackupnext.ui.util.groupedItemShape

/**
 * 设置页 / 关于页 / 开源声明页共用的一套「分组卡片」。
 *
 * 形状和 apptab 的列表卡片（[com.cc.libackupnext.ui.util.AppListContainer]）完全一致：
 * 圆角按组内位置由 [groupedItemShape] 算，行与行之间只隔 [GroupVerticalSpacing]，
 * 没有分割线 —— 一组看起来就是一张被切成几段的卡片。
 */

@Composable
internal fun SectionTitle(text: String) {
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
internal fun CardGroup(
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
internal fun <T> LazyListScope.cardGroup(
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
internal fun SettingsCard(
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
