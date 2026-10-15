package com.cc.libackupnext.ui.dialog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 备份说明弹窗，应用详情页和批量页共用。
 *
 * @param extraLines 追加在最上面的说明（批量页用它讲勾选操作）
 */
@Composable
fun BackupHelpDialog(
    extraLines: List<String> = emptyList(),
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("帮助") },
        text = {
            Column {
                extraLines.forEach { line ->
                    Text(line)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                if (extraLines.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                }
                Text("apk：应用安装包（含 obb），支持分包应用 (Split APK)")
                Spacer(modifier = Modifier.height(8.dp))
                Text("user：位于 /data/user/ 下的数据")
                Spacer(modifier = Modifier.height(8.dp))
                Text("data：位于共享存储 /Android/data/ 下的数据")
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "权限：已授予的运行时权限随 user 数据一起备份，恢复时重新授予；" +
                            "「仅本次允许」这类临时授权不备份"
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("确定")
            }
        }
    )
}
