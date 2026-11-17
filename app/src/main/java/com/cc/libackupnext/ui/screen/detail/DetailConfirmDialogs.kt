package com.cc.libackupnext.ui.screen.detail

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.cc.libackupnext.ui.component.AppDialog

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
    AppDialog(
        title = title,
        onDismiss = onDismiss,
        confirmText = "确定",
        onConfirm = onConfirm,
        destructive = true
    ) {
        Text(message)
    }
}
