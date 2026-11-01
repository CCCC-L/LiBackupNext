package com.cc.libackupnext.ui.screen.detail

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * 破坏性操作的二次确认。
 *
 * 只负责画：标题和正文都由调用方给 —— 这个弹窗不关心「卸载」和「删文件夹」有什么区别，
 * 那些是业务语义。
 */
@Composable
fun ConfirmActionDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("确定", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}


/** 「删除全部备份」的二次确认。 */
@Composable
fun ConfirmDeleteAllDialog(
    appName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除全部备份") },
        text = { Text("确定要删除「$appName」的全部备份吗？此操作不可撤销。") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("确定", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
