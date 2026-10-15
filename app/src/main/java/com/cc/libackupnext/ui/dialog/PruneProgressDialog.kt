package com.cc.libackupnext.ui.dialog

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 整理仓库（prune）进行中的提示：不可取消、不可关闭，避免中途退出损坏仓库。
 */
@Composable
fun PruneProgressDialog() {
    AlertDialog(
        onDismissRequest = { },
        title = { Text("正在整理仓库") },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "正在清理无用的数据，请勿关闭应用…",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = { }
    )
}
