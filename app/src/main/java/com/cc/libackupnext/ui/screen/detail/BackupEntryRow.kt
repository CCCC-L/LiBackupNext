package com.cc.libackupnext.ui.screen.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cc.libackupnext.data.BackupEntry
import com.cc.libackupnext.ui.util.AppItemCard
import com.cc.libackupnext.ui.util.formatDateTime

/**
 * 一条备份记录。
 *
 * 纯展示：勾选、删除、恢复这些状态都由调用方持有，这里只把点击事件抛出去。
 *
 * @param isLatest 是不是最新一条（第一条用强调色显示时间）
 * @param onManage 右侧「管理」按钮（整块区域都可点，不只按钮本身）
 */
@Composable
fun BackupEntryRow(
    entry: BackupEntry,
    isLatest: Boolean,
    shape: Shape,
    latestSubtitleColor: Color,
    bottomTextColor: Color,
    onClick: () -> Unit,
    onManage: () -> Unit,
) {
    AppItemCard(
        icon = null,
        title = entry.title,
        subtitle = listOfNotNull(entry.userName, formatDateTime(entry.time))
            .joinToString(" · "),
        bottomText = entry.description ?: "无备注",
        onClick = onClick,
        shape = shape,
        titleStyle = MaterialTheme.typography.labelLarge,
        titleFontWeight = FontWeight.Normal,
        subtitleColor = if (isLatest) {
            latestSubtitleColor
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        bottomTextColor = bottomTextColor,
        // 右侧不留卡片内边距，交给下面的可点区域撑到卡片边缘
        endPadding = 0.dp,
        trailingContent = {
            // 按钮四周再留一圈「也算点了管理」的区域
            val manageInteraction = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .clickable(
                        interactionSource = manageInteraction,
                        indication = null
                    ) { onManage() }
                    .padding(start = 8.dp, end = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                FilledTonalButton(
                    onClick = onManage,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text("管理")
                }
            }
        }
    )
}
