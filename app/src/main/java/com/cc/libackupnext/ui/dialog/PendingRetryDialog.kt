package com.cc.libackupnext.ui.dialog

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cc.libackupnext.ui.component.AppSurfaceDialog
import kotlinx.coroutines.delay

/** 和 [rememberDoubleClickGuard] 的默认窗口保持一致：过了这个时间待确认态就复位。 */
private const val DISCARD_CONFIRM_WINDOW_MS = 300L

/**
 * 点「继续未完成」后的确认窗。
 *
 * 按钮区自己排版：放弃固定在最左边，取消和继续靠最右。
 * AlertDialog 的按钮行是右对齐的 FlowRow，做不到这个布局，所以用 [Dialog] + [Surface]。
 *
 * - 放弃：双击确认（第一次点变红字提示），只清空待办，不碰已备份 / 恢复的内容
 * - 取消：关掉弹窗回到原页面，待办原样保留
 * - 继续：真正跳回发起页面续跑
 */
@Composable
fun PendingRetryDialog(
    count: Int,
    onDiscard: () -> Unit,
    onCancel: () -> Unit,
    onContinue: () -> Unit
) {
    val discardGuard = rememberDoubleClickGuard()
    // 点外部 / 返回键等同「取消」：什么都不做，回到上一层
    AppSurfaceDialog(
        onDismissRequest = onCancel,
        title = "是否继续未完成的任务"
    ) {
        Text(
            text = "上次有 $count 项未完成。已成功的内容不会重复执行；" +
                    "放弃只会清空待办，不会删除已备份或已恢复的内容。",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(modifier = Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(
                onClick = {
                    if (discardGuard.consume("discard")) {
                        onDiscard()
                    }
                }
            ) {
                Text(
                    text = "放弃",
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Medium
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onCancel) {
                Text("取消")
            }
            TextButton(onClick = onContinue) {
                Text("继续")
            }
        }
    }
}
