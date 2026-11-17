package com.cc.libackupnext.ui.screen.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cc.libackupnext.data.BackupEntry
import com.cc.libackupnext.data.BackupGroup
import com.cc.libackupnext.data.BackupTarget
import com.cc.libackupnext.ui.component.AppSurfaceDialog
import com.cc.libackupnext.ui.theme.heighLightColor
import com.cc.libackupnext.ui.util.formatSize

/**
 * 单条备份的「管理」弹窗。
 *
 * 纯展示：勾选状态由 [checkedKinds] 传入，任何按钮点击都抛给调用方 —— 双击防误触、
 * 业务调用都留在外层，这样这个弹窗可以独立预览。
 *
 * @param target 目标类型：决定列哪几个勾选单位（apk/user/data、dir、wifi……）
 * @param canOverwrite 应用没装时没有可覆盖的内容，按钮不显示
 */
@Composable
fun BackupManageDialog(
    entry: BackupEntry,
    target: BackupTarget,
    /** 这条备份的版本和当前装的版本不一致：标题里的版本号标蓝提示。 */
    isVersionChanged: Boolean,
    canOverwrite: Boolean,
    checkedKinds: Set<BackupGroup>,
    onCheckedChange: (BackupGroup, Boolean) -> Unit,
    onDismiss: () -> Unit,
    onOverwrite: () -> Unit,
    onDelete: () -> Unit,
    onRestore: () -> Unit,
) {
    AppSurfaceDialog(
        onDismissRequest = onDismiss,
        titleContent = {
            // 样式由 AppSurfaceDialog 提供（与 AlertDialog 的标题一致），这里只拼内容
            Text(
                text = buildAnnotatedString {
                    append(entry.title)
                    // 系统数据（Wi-Fi / 联系人 / 通话记录 / 短信）没有版本号，别挂一个「null」
                    entry.version?.takeIf { it.isNotBlank() }?.let { version ->
                        append(" ")
                        withStyle(SpanStyle(
                            // 标题是 20sp Bold，版本号跟着放大一档、但压回常规字重，
                            // 否则和标题一样粗，层级就没了
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Normal,
                            color = if (isVersionChanged) MaterialTheme.colorScheme.heighLightColor else Color.Unspecified
                        )) {
                            append(version)
                        }
                    }
                }
            )
        }
    ) {
        target.kinds.forEach { group ->
            val snaps = entry.snapshotsOf(group)
            val exists = snaps.isNotEmpty()
            ManageOptionRow(
                label = group.title,
                checked = group in checkedKinds,
                enabled = exists,
                size = if (exists) formatSize(snaps.sumOf { it.size }) else "",
                id = if (exists) snaps.joinToString(" ") { it.id.take(8) } else "",
                onCheckedChange = { onCheckedChange(group, it) }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            if (canOverwrite) {
                TextButton(onClick = onOverwrite) {
                    Text("覆盖备份")
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDelete) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
            TextButton(onClick = onRestore) {
                Text("恢复")
            }
        }
    }
}

/** 管理弹窗里的一行：勾选框 + 类型名 + 大小 + 快照 id。 */
@Composable
private fun ManageOptionRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    size: String,
    id: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Checkbox) { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 和备份选项弹窗同一个组件。onCheckedChange 必须非 null —— M3 只在非 null 时才套
        // minimumInteractiveComponentSize，传 null 会让 Checkbox 缩成 18dp、整行行高塌掉
        Checkbox(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = size,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (id.isNotBlank()) {
                Text(
                    text = id,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}
