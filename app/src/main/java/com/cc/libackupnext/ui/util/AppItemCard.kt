package com.cc.libackupnext.ui.util

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun AppItemCard(
    icon: ImageBitmap?,
    title: String,
    subtitle: String,
    bottomText: String,
    shape: Shape,
    onClick: (() -> Unit)? = null,
    /** 非空时即使 [icon] 还没加载完也保留图标位，并显示首字母占位。 */
    iconPlaceholder: String? = null,
    /** 已冻结的应用在标题前显示小雪花。 */
    frozen: Boolean = false,
    /** 只在备份里存在（未安装）：标题划线、图标置灰。 */
    dimmed: Boolean = false,
    trailingContent: (@Composable () -> Unit)? = null,
    titleStyle: TextStyle = MaterialTheme.typography.titleMedium,
    titleFontWeight: FontWeight = FontWeight.Bold,
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
            if (icon != null || iconPlaceholder != null) {
                AppIcon(
                    icon = icon,
                    name = iconPlaceholder ?: title,
                    modifier = Modifier
                        .padding(vertical = 12.dp)
                        .size(48.dp)
                        .alpha(if (dimmed) 0.45f else 1f)
                )
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
                        color = MaterialTheme.colorScheme.onSurface,
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
