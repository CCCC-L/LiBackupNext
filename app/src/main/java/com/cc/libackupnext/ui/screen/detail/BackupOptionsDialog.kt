package com.cc.libackupnext.ui.screen.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.cc.libackupnext.ui.theme.dialogContainer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.cc.libackupnext.data.BackupGroup
import com.cc.libackupnext.data.BackupTarget

/**
 * 备份选项弹窗。
 *
 * 应用：apk / user / data 三选
 * 文件夹 / 系统数据 没有可选项，只有一句确认。
 */
@Composable
fun BackupOptionsDialog(
    target: BackupTarget,
    dirPath: String,
    checked: Set<BackupGroup>,
    onToggle: (BackupGroup, Boolean) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.dialogContainer,
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "备份",
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column {
                if (target.kinds.size == 1) {
                    // 整个全备，没有可选项
                    Text(
                        text = if (target == BackupTarget.DIR) {
                            "确定备份「$dirPath」么？"
                        } else {
                            "确定备份「${target.displayName}」么？"
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    // 勾哪几项由 target.kinds 决定，不在这里写死
                    target.kinds.forEach { group ->
                        BackupOptionRow(
                            label = group.optionLabel,
                            checked = group in checked,
                            onCheckedChange = { onToggle(group, it) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                // 没有可选项的目标永远可以备份；应用至少要勾一项
                enabled = target.kinds.size == 1 || checked.isNotEmpty()
            ) {
                Text("备份")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

/** 一个方形勾选项：选中时填强调色 + 打勾。 */
@Composable
private fun BackupOptionRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val accent = MaterialTheme.colorScheme.primary
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (checked) accent else Color.Transparent)
                .border(
                    width = 1.5.dp,
                    color = if (checked) accent else MaterialTheme.colorScheme.outline,
                    shape = RoundedCornerShape(4.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (checked) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(13.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}
