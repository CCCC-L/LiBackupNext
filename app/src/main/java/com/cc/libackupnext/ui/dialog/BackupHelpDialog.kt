package com.cc.libackupnext.ui.dialog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import com.cc.libackupnext.ui.theme.dialogContainer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 帮助弹窗，详情页和批量页共用。
 *
 * 内容由调用方给：详情页传 `target.helpLines`，批量页先列勾选操作、再拼上这一页
 * 涉及的目标的说明。
 *
 * @param lines 一行一段
 */
@Composable
fun BackupHelpDialog(
    lines: List<String>,
    onDismiss: () -> Unit
) {
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.dialogContainer,
        onDismissRequest = onDismiss,
        title = { Text("帮助") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                lines.forEachIndexed { index, line ->
                    if (index > 0) Spacer(modifier = Modifier.height(8.dp))
                    Text(line)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("确定")
            }
        }
    )
}
