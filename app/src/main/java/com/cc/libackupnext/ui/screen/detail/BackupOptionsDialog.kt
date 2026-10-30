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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 备份选项弹窗。
 *
 * 应用：apk / user / data 三选；目录：只有一句确认（整个文件夹全备，没有可选项）。
 */
@Composable
fun BackupOptionsDialog(
    isDirectory: Boolean,
    dirPath: String,
    backupApk: Boolean,
    backupUser: Boolean,
    backupData: Boolean,
    onApkChange: (Boolean) -> Unit,
    onUserChange: (Boolean) -> Unit,
    onDataChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "备份",
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column {
                if (isDirectory) {
                    Text(
                        text = "确定备份「$dirPath」么？",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    BackupOptionRow("apk(含 obb)", backupApk, onApkChange)
                    BackupOptionRow("user", backupUser, onUserChange)
                    BackupOptionRow("data", backupData, onDataChange)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = isDirectory || backupApk || backupUser || backupData
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
