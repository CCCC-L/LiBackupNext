package com.cc.libackupnext.ui.screen.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cc.libackupnext.ui.dialog.AppOptionsMenu
import com.cc.libackupnext.ui.util.dimmedWhen

/**
 * 详情页头部：图标 + 名称 / 副标题 / 版本 + 右侧「备份」按钮。
 *
 * 目标是应用、文件夹还是以后的 Wi-Fi，全由 [DetailTarget] 描述，这里只负责排版。
 */
@Composable
fun DetailHeader(
    target: DetailTarget,
    isBusy: Boolean,
    modifier: Modifier = Modifier,
) {
    var showOptions by remember { mutableStateOf(false) }

    // 目标已经不在了（应用没装 / 文件夹删了）：菜单和备份按钮一起置灰。
    // 恢复入口在下面的备份列表里，不靠这个菜单。
    val canInteract = !isBusy && !target.isUninstalled
    // 系统数据这类目标没有菜单动作，点标题区弹一个空菜单很怪
    val hasActions = target.actions.isNotEmpty()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 图标 + 文字区域：点击弹出操作菜单
        Box(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(enabled = canInteract && hasActions) { showOptions = true }
                    .dimmedWhen(target.isUninstalled)
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                target.Icon(modifier = Modifier.size(48.dp))
                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        target.Badge()
                        Text(
                            text = target.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textDecoration = if (target.isUninstalled) {
                                TextDecoration.LineThrough
                            } else {
                                null
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = target.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    target.version?.let { version ->
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = version,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            AppOptionsMenu(
                expanded = showOptions,
                actions = target.actions,
                onDismiss = { showOptions = false },
                onAction = {
                    showOptions = false
                    target.onAction(it)
                }
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        if (isBusy) {
            Box(
                modifier = Modifier.size(44.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp
                )
            }
        } else {
            FilledTonalButton(
                onClick = target.onBackup,
                enabled = canInteract,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Text("备份")
            }
        }
    }
}
