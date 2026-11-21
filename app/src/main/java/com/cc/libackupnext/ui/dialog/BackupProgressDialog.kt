package com.cc.libackupnext.ui.dialog

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.cc.libackupnext.data.BackupStep
import com.cc.libackupnext.data.SnapshotKind
import com.cc.libackupnext.data.BackupStepStatus
import com.cc.libackupnext.service.gateway.BackendErrors
import com.cc.libackupnext.ui.component.AppSurfaceDialog
import com.cc.libackupnext.util.AppLog

/**
 * 进度弹窗里的一段：标题（应用名，可为空）+ 这一步的步骤。
 *
 * 批量操作时每处理完一个应用就追加一段，之前成功的记录不会被清掉。
 */
data class ProgressSection(
    val title: String,
    val steps: List<BackupStep>
)

/**
 * 备份 / 恢复 / 删除的进度弹窗，应用详情页和批量页共用。
 *
 * 运行中不可关闭（返回键、点外部都无效），结束后点「关闭」退出。
 *
 * @param subtitle 副标题，例如「开始:com.xxx」或「3/10  应用名」
 * @param sections 已处理过的步骤；批量时按应用分组，全部保留
 * @param isRestore 步骤文案用「恢复」还是「备份」
 */
@Composable
fun BackupProgressDialog(
    title: String,
    /** 跑完且没有错误时显示的标题，例如「备份成功」。 */
    doneTitle: String,
    subtitle: String,
    sections: List<ProgressSection>,
    isRestore: Boolean,
    error: String?,
    running: Boolean,
    onDismiss: () -> Unit,
    /** 远程仓库连接中断：弹窗内改标题 + 一行红色提示。 */
    connectionInterrupted: Boolean = false
) {
    // 有文件读不出来就不能说「成功」：快照是建出来了，但里面少了东西
    val unreadable = unreadableFiles(sections)

    AppSurfaceDialog(
        onDismissRequest = { /* 运行过程中不允许关闭 */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        ),
        title = when {
            connectionInterrupted -> "远程仓库连接中断"
            // 这里不猜动词（备份 / 覆盖 / 恢复），免得说错，只说事实
            !running && error == null && unreadable > 0 -> "已完成，$unreadable 个文件未能读取"
            !running && error == null -> doneTitle
            else -> title
        }
    ) {
        Text(text = subtitle, style = MaterialTheme.typography.bodySmall)

        // 步骤多的时候滚动，新加的一段自动滚到底
        val scrollState = rememberScrollState()

        // 是否贴着底部。默认跟随；用户手动往上翻看历史后就停止跟随
        var stickToBottom by remember { mutableStateOf(true) }

        // 只在「用户自己滑动结束」时更新状态，避免程序滚动和内容变高互相打架
        LaunchedEffect(scrollState) {
            snapshotFlow { scrollState.isScrollInProgress }.collect { dragging ->
                if (!dragging) {
                    stickToBottom = scrollState.value >= scrollState.maxValue - 16
                }
            }
        }

        // 内容变高（新的一段进来）时，贴着底部才跟着滚
        LaunchedEffect(scrollState.maxValue) {
            if (stickToBottom) scrollState.scrollTo(scrollState.maxValue)
        }
        Column(
            modifier = Modifier
                // 撑满宽度：文字右边的空白区域也能拖动滚动
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .verticalScroll(scrollState)
        ) {
            sections.forEach { section ->
                if (section.title.isNotBlank()) {
                    Text(
                        text = section.title,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                section.steps
                    .filter { it.status != BackupStepStatus.WAITING }
                    .forEach { step ->
                        Text(
                            text = "${backupStepTitle(step.kind, isRestore)}:" +
                                    backupStepStatusText(step) +
                                    unreadableSuffix(step),
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (step.status == BackupStepStatus.SKIPPED &&
                            step.reason.isNotBlank()
                        ) {
                            Text(
                                text = step.reason,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
            }
        }

        if (connectionInterrupted || error != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (connectionInterrupted) {
                    BackendErrors.REMOTE_INTERRUPTED_MESSAGE
                } else {
                    error.orEmpty()
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onDismiss, enabled = !running) {
                Text("关闭")
            }
        }
    }
}

/** 步骤标题：`备份apk` / `恢复user` 这种。 */
private fun backupStepTitle(kind: SnapshotKind, isRestore: Boolean): String =
    (if (isRestore) "恢复" else "备份") + kind.key

private fun backupStepStatusText(step: BackupStep): String = when (step.status) {
    BackupStepStatus.WAITING -> "等待"
    BackupStepStatus.RUNNING -> "进行中"
    BackupStepStatus.DONE -> "完成"
    BackupStepStatus.SKIPPED -> "跳过"
    BackupStepStatus.FAILED -> "失败"
}

/** 这一步有文件读不出来时在UI中显示。 */
private fun unreadableSuffix(step: BackupStep): String =
    if (step.errors > 0) "（${step.errors} 个文件未能读取）" else ""

/** 所有步骤加起来的未读取文件数，用来判断这次操作能不能叫「成功」。 */
private fun unreadableFiles(sections: List<ProgressSection>): Long =
    sections.sumOf { section -> section.steps.sumOf { it.errors } }

/**
 * 双击确认守卫：同一个动作 1 秒内点两次才算确认。
 *
 * 第一次点击只提示 [hint]，不执行操作，避免误触删除 / 覆盖这类危险按钮。
 */
class DoubleClickGuard(
    private val windowMillis: Long = 300L,
    private val onFirstClick: () -> Unit = {}
) {
    private var pendingAction: String? = null
    private var pendingAt = 0L

    /** 返回 true 表示构成双击，可以执行操作。 */
    fun consume(action: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        return if (pendingAction == action && now - pendingAt < windowMillis) {
            pendingAction = null
            true
        } else {
            pendingAction = action
            pendingAt = now
            onFirstClick()
            false
        }
    }

    /** 取消待确认状态（例如弹窗关闭时）。 */
    fun reset() {
        pendingAction = null
    }
}

@Composable
fun rememberDoubleClickGuard(hint: String = "双击执行"): DoubleClickGuard =
    remember(hint) {
        // 全应用共用的 Toast：新提示会替换上一条，不会一条条排队
        DoubleClickGuard { AppLog.toastOnMain(hint) }
    }
