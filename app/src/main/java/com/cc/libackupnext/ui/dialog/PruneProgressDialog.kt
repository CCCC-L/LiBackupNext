package com.cc.libackupnext.ui.dialog

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.cc.libackupnext.ui.component.LoadingRow
import com.cc.libackupnext.ui.component.AppDialog

/**
 * 整理仓库（prune）进行中的提示：不可取消、不可关闭，避免中途退出损坏仓库。
 */
@Composable
fun PruneProgressDialog() {
    AppDialog(
        title = "正在整理仓库",
        onDismiss = { },
        dismissText = null
    ) {
        LoadingRow {
            Text(
                text = "正在清理无用的数据，请勿关闭应用…",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
