package com.cc.libackupnext.ui.screen.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cc.libackupnext.entity.AppInfo
import com.cc.libackupnext.ui.UserBadge
import com.cc.libackupnext.ui.dialog.AppAction
import com.cc.libackupnext.ui.dialog.AppOptionsMenu
import com.cc.libackupnext.ui.isNonPrimaryUser
import com.cc.libackupnext.ui.util.AppIcon
import com.cc.libackupnext.ui.util.FrozenSnowflake
import com.cc.libackupnext.ui.util.rememberAppIconWithBackup

/**
 * 详情页头部：图标 + 名称 / 包名 / 版本 + 右侧「备份」按钮。
 *
 * 纯展示组件 —— 点图标弹操作菜单、点备份按钮，都由调用方处理。
 *
 * @param iconContent 文件夹等自定义图标，传了就不画应用图标
 * @param latestVersion 应用没装时用最新备份里记录的版本兜底
 */
@Composable
fun DetailHeader(
    app: AppInfo,
    iconContent: (@Composable () -> Unit)?,
    isFrozen: Boolean,
    isUninstalled: Boolean,
    canInteract: Boolean,
    isBusy: Boolean,
    isDirectory: Boolean,
    showOptions: Boolean,
    latestVersion: String?,
    onOpenOptions: () -> Unit,
    onDismissOptions: () -> Unit,
    onAction: (AppAction) -> Unit,
    onBackup: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
                    .clickable(enabled = canInteract) { onOpenOptions() }
                    .alpha(if (isUninstalled) 0.45f else 1f)
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.size(48.dp)) {
                    if (iconContent != null) {
                        iconContent()
                    } else {
                        AppIcon(
                            icon = rememberAppIconWithBackup(app.packageName, app.userId),
                            name = app.name,
                            modifier = Modifier.fillMaxSize(),
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                    if (isNonPrimaryUser(app.userId)) {
                        UserBadge(
                            userId = app.userId,
                            compact = true,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(2.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isFrozen) {
                            FrozenSnowflake()
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text(
                            text = app.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textDecoration = if (isUninstalled) {
                                TextDecoration.LineThrough
                            } else {
                                null
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = app.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    val version = app.version.ifBlank { latestVersion.orEmpty() }
                    if (version.isNotBlank()) {
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
                frozen = isFrozen,
                onDismiss = onDismissOptions,
                onAction = onAction,
                isDirectory = isDirectory
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
                onClick = onBackup,
                enabled = canInteract,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Text("备份")
            }
        }
    }
}
