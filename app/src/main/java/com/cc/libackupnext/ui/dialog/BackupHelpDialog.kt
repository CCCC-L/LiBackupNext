package com.cc.libackupnext.ui.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cc.libackupnext.ui.component.AppDialog

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
    AppDialog(
        title = "帮助",
        onDismiss = onDismiss,
        confirmText = "确定",
        onConfirm = onDismiss,
        dismissText = null
    ) {
        // 单独一层 Column：AppDialog 的内容槽没有 modifier 参数，滚动要挂在自己的容器上
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            lines.forEach { line -> Text(line) }
        }
    }
}
