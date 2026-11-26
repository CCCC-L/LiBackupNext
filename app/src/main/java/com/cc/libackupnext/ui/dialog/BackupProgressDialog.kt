package com.cc.libackupnext.ui.dialog

import android.os.SystemClock
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.cc.libackupnext.data.BackupStep
import com.cc.libackupnext.data.BackupStepStatus
import com.cc.libackupnext.data.SnapshotKind
import com.cc.libackupnext.service.gateway.BackendErrors
import com.cc.libackupnext.ui.component.AppSurfaceDialog
import com.cc.libackupnext.ui.util.formatSize
import com.cc.libackupnext.util.AppLog
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * 「重建媒体数据库」这一步的标题。
 *
 * 它不是快照类型，没有「备份 / 恢复」前缀可加；顶部和步骤列表共用这一份文案，
 * 免得两处各写一遍再改漏。
 */
private const val MEDIA_STEP_TITLE = "重建媒体数据库"

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
 * @param error 一行错误文案（结论 + 原因开头）
 * @param errorDetail 完整原文（可能多行），滚在步骤列表末尾
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
    connectionInterrupted: Boolean = false,
    errorDetail: String? = null
) {
    // 有文件读不出来就不能说「成功」：快照是建出来了，但里面少了东西
    val unreadable = unreadableFiles(sections)

    // 断连结论一给出就按「已结束」渲染：不等 :root 里的 nativeBackup 收尾
    // （cancel 只让后续读写立刻失败，在途请求还得等自己的超时）。
    val effectiveRunning = running && !connectionInterrupted

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
        // 进度区固定在滚动区之外：滚动的是历史步骤，当前进度必须一直看得见
        ProgressHeader(
            subtitle = subtitle,
            sections = sections,
            running = effectiveRunning,
            isRestore = isRestore
        )

        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
        Spacer(modifier = Modifier.height(8.dp))

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
                // 比原来矮 40dp：顶部多了进度区，弹窗总高度保持不变
                .heightIn(max = 280.dp)
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
                        // 任何状态下的 reason 都要显示：跳过要说明原因，
                        // 恢复目录的「正在更新相册索引…」也是挂在 done 上的
                        if (step.reason.isNotBlank()) {
                            Text(
                                text = step.reason,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
            }

            // 完整错误放在滚动区末尾：rustic 的 Message / Caused by 可能很长，
            // 让用户滚着看完，不挤占上面那行固定可见的结论
            val detail = errorDetail?.takeIf { it.isNotBlank() && it != error }
            if (detail != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 断连时没有 error 才用兜底文案：正常都带着具体原因（HTTP 500 / 连接被拒）
        val errorText = if (connectionInterrupted) {
            error?.takeIf { it.isNotBlank() } ?: BackendErrors.REMOTE_INTERRUPTED_MESSAGE
        } else {
            error?.takeIf { it.isNotBlank() }
        }
        if (errorText != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = errorText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onDismiss, enabled = !effectiveRunning) {
                Text("关闭")
            }
        }
    }
}

/**
 * 弹窗顶部固定的进度区：状态一行 + 进度条 + 当前步骤 + 已用时间。
 *
 * 进度条有两种形态，由 rustic 扫没扫出源目录总量决定：
 * - 备份刚起步的几秒（rustic 在独立线程里扫源目录），没有分母 → 不确定态（流动）；
 * - 拿到 total 之后 → 确定态，封顶 99%（扫出来的总量可能比实际略小）。
 *
 * 时间从弹窗第一次组合开始算：两个调用方都是在把 running 置 true 的同一帧里打开弹窗。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ProgressHeader(
    subtitle: String,
    sections: List<ProgressSection>,
    running: Boolean,
    isRestore: Boolean
) {
    // 副标题行右侧要挂当前步骤的传输量
    val step = if (running) runningStep(sections) else null
    val transferred = step?.transferred ?: 0L

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            // 应用名可能很长：传输量优先占位，副标题自己省略
            modifier = Modifier.weight(1f, fill = false),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        // 只有远程仓库有传输量：rustic 的字节进度到打包器为止，一个 pack 传完之前 current 不动
        if (transferred > 0) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = transferText(transferred, step?.transferSpeed ?: 0L, isRestore),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }

    val startedAt = remember { SystemClock.elapsedRealtime() }
    var now by remember { mutableLongStateOf(startedAt) }
    LaunchedEffect(running) {
        while (running) {
            now = SystemClock.elapsedRealtime()
            delay(1_000)
        }
        // 结束的那一下补一次，把最后一秒也算进去
        now = SystemClock.elapsedRealtime()
    }
    val elapsed = (now - startedAt).coerceAtLeast(0)

    if (running) {
        val total = step?.total ?: 0L
        val current = step?.current ?: 0L
        val hasLength = total > 0

        // 目标比例。封顶 0.99：扫出来的总量可能比实际略小，到了 100% 还在写最伤人
        val target = if (hasLength) {
            (current.toDouble() / total).coerceIn(0.0, 0.99).toFloat()
        } else {
            0f
        }
        // 线性缓动：进度要匀速追赶。用 ease-in-out 会一顿一顿，反而显得卡
        val fraction by animateFloatAsState(
            targetValue = target,
            animationSpec = tween(durationMillis = 350, easing = LinearEasing),
            label = "backup-progress"
        )
        // 波幅固定：默认实现会随进度衰减，99% 时压成一条直线
        val amplitude = with(LocalDensity.current) { 3.dp.toPx() }

        Spacer(modifier = Modifier.height(10.dp))
        LinearWavyProgressIndicator(
            progress = { fraction },
            amplitude = { amplitude },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = currentStepText(sections),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = if (hasLength) {
                    "${percent(current, total)}%"
                } else {
                    "已用 ${formatElapsed(elapsed)}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(2.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = if (hasLength) {
                    // 媒体索引这一步的 total / current 是文件数，不是字节
                    if (step?.kind == SnapshotKind.MEDIA) {
                        "${current} / ${total} 个文件"
                    } else {
                        "${formatSize(current.coerceAtMost(total))} / ${formatSize(total)}"
                    }
                } else {
                    // 没有分母时不画流动条：条停在 0%，用文字说明还在数
                    "正在统计总量…"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (hasLength) {
                Text(
                    text = "已用 ${formatElapsed(elapsed)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    } else if (elapsed >= 1_000) {
        // 结束后不再画进度条：一条停在原地的满条没有任何信息量
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "用时 ${formatElapsed(elapsed)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 当前正在处理的那一步。
 *
 * 从最后一段往前找：批量时当前应用是最后一段，前面几段都是已经跑完的历史。
 */
private fun runningStep(sections: List<ProgressSection>): BackupStep? =
    sections.asReversed().firstNotNullOfOrNull { section ->
        section.steps.lastOrNull { it.status == BackupStepStatus.RUNNING }
    }

private fun currentStepText(sections: List<ProgressSection>): String {
    val step = runningStep(sections) ?: return "正在准备…"
    // 和下面步骤列表保持一致：同一个步骤不能顶上一句、列表里另一句
    return if (step.kind == SnapshotKind.MEDIA) MEDIA_STEP_TITLE else step.kind.key
}

/** 0..99：到 100% 就说明分母不准了，宁可停在 99 等真正完成。 */
private fun percent(current: Long, total: Long): Int =
    if (total <= 0) 0 else ((current * 100) / total).coerceIn(0L, 99L).toInt()

/** `1:23` / `12:05` / `1:02:03`。 */
private fun formatElapsed(millis: Long): String {
    val total = millis / 1_000
    val hours = total / 3_600
    val minutes = (total % 3_600) / 60
    val seconds = total % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

/** 传输量文案：`已上传 680.0 MB · 8.4 MB/s`。速度为 0 时不写，免得看起来像停了。 */
private fun transferText(transferred: Long, speed: Long, isRestore: Boolean): String {
    val verb = if (isRestore) "已下载" else "已上传"
    return if (speed > 0) {
        "$verb ${formatSize(transferred)} · ${formatSize(speed)}/s"
    } else {
        "$verb ${formatSize(transferred)}"
    }
}

/** 步骤标题：`备份apk` / `恢复user` 这种。 */
private fun backupStepTitle(kind: SnapshotKind, isRestore: Boolean): String = when (kind) {
    SnapshotKind.MEDIA -> MEDIA_STEP_TITLE
    else -> (if (isRestore) "恢复" else "备份") + kind.key
}

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
