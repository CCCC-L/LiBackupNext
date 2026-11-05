package com.cc.libackupnext.ui.screen.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import com.cc.libackupnext.ui.theme.dialogContainer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * 备份备注编辑弹窗。
 *
 * 用自定义 Dialog 而不是 AlertDialog，是为了把「移除备注」放到最左边 ——
 * AlertDialog 的按钮区只有左右两个位置。
 *
 * @param note 当前输入内容（状态由调用方持有）
 */
@Composable
fun NoteEditDialog(
    note: String,
    onNoteChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onRemove: () -> Unit,
    onConfirm: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.dialogContainer
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "备注",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = note,
                    onValueChange = onNoteChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("备注内容") },
                    maxLines = 4
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 移除备注放最左边，其余靠右
                    TextButton(onClick = onRemove) {
                        Text("移除备注", color = MaterialTheme.colorScheme.error)
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    TextButton(onClick = onDismiss) {
                        Text("取消")
                    }
                    TextButton(onClick = onConfirm) {
                        Text("确定")
                    }
                }
            }
        }
    }
}
