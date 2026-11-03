package com.cc.libackupnext.ui.util

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
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
    titleStyle: TextStyle = MaterialTheme.typography.titleMedium,
    titleFontWeight: FontWeight = FontWeight.Bold,
    /** 标题颜色；null 用默认（不存在时灰、否则 onSurface）。系统应用传红色。 */
    titleColor: Color? = null,
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

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (frozen) {
                        FrozenSnowflake()
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        text = title,
                        style = titleStyle,
                        fontWeight = titleFontWeight,
                        color = titleColor ?: missingTitleColor(dimmed),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textDecoration = if (dimmed) TextDecoration.LineThrough else null,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = subtitleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = bottomText,
                    style = MaterialTheme.typography.labelSmall,
                    color = bottomTextColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (extraBottomText != null) {
                    Spacer(modifier = Modifier.height(1.dp))
                    Text(
                        text = extraBottomText,
                        style = MaterialTheme.typography.labelSmall,
                        color = subtitleColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (trailingContent != null) {
                Spacer(modifier = Modifier.width(12.dp))
                trailingContent()
            }
        }
    }
}
