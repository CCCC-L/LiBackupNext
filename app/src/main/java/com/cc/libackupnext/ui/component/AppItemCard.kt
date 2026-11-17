package com.cc.libackupnext.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun AppItemCard(
    title: String,
    subtitle: String,
    bottomText: String,
    shape: Shape,
    onClick: (() -> Unit)? = null,
    /** 图标：应用 / 文件夹 / 系统数据都从这里进，不传就不留图标位。 */
    icon: (@Composable () -> Unit)? = null,
    /** 已冻结的应用在标题前显示小雪花。 */
    frozen: Boolean = false,
    /** 只在备份里存在（未安装）：标题划线、图标置灰。 */
    dimmed: Boolean = false,
    trailingContent: (@Composable () -> Unit)? = null,
    /** 标题文字后面的附加内容（如「当前使用中」标签），默认没有。 */
    titleTrailing: (@Composable () -> Unit)? = null,
    /** 信息密度：主列表 [RowTextDensity.Regular]，详情页的备份记录行 [RowTextDensity.Dense]。 */
    density: RowTextDensity = RowTextDensity.Regular,
    /** 目标状态：系统应用的标题标红。目标不存在由 [dimmed] 表达，不用再传。 */
    tone: RowTone = RowTone.Normal,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    bottomTextColor: Color = MaterialTheme.colorScheme.outline,
    extraBottomText: String? = null,
    endPadding: androidx.compose.ui.unit.Dp = 16.dp
) {
    val clickModifier = if (onClick != null) {
        Modifier.clickable(onClick = onClick)
    } else {
        Modifier
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(clickModifier),
        shape = shape,
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(start = 16.dp, end = endPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Box(
                    modifier = Modifier
                        .padding(vertical = 12.dp)
                        .size(48.dp)
                        .dimmedWhen(dimmed),
                    contentAlignment = Alignment.Center
                ) {
                    icon()
                }
                Spacer(modifier = Modifier.width(14.dp))
            }

            RowTextBlock(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 12.dp),
                density = density,
                tone = if (dimmed) RowTone.Missing else tone,
                title = AnnotatedString(title),
                subtitle = AnnotatedString(subtitle),
                caption = AnnotatedString(bottomText),
                subtitleColor = subtitleColor,
                captionColor = bottomTextColor,
                titleLeading = if (frozen) {
                    {
                        FrozenSnowflake()
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                } else {
                    null
                },
                titleTrailing = titleTrailing,
                extra = if (extraBottomText != null) {
                    {
                        Text(
                            text = extraBottomText,
                            style = MaterialTheme.typography.labelSmall,
                            color = subtitleColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    null
                }
            )

            if (trailingContent != null) {
                Spacer(modifier = Modifier.width(12.dp))
                trailingContent()
            }
        }
    }
}
