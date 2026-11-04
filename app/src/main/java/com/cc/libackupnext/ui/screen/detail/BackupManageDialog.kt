package com.cc.libackupnext.ui.screen.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Color.Companion
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.cc.libackupnext.data.BackupEntry
import com.cc.libackupnext.data.BackupGroup
import com.cc.libackupnext.data.BackupTarget
import com.cc.libackupnext.ui.theme.AppColors
import com.cc.libackupnext.ui.theme.LocalAppColors
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
    val appColors = LocalAppColors.current

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
//                    text = entry.title,
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                            append(entry.title)
                        }
                        append(" ")
                        withStyle(SpanStyle(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        )) {
                            append((entry.version))
                        }

                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(12.dp))

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
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(start = 4.dp)
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
