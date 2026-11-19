package com.cc.libackupnext.ui.component

import android.view.Window
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.cc.libackupnext.ui.theme.dialogContainer

/**
 * 弹窗标题的统一样式。
 *
 * 两套壳都用它：走 AlertDialog 的 [AppDialog] 和走自定义 Surface 的 [AppSurfaceDialog]。
 */
val AppDialogTitleStyle: TextStyle
    @Composable get() = MaterialTheme.typography.headlineSmall.copy(
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.Bold
    )

/**
 * 标题和正文之间的距离。
 *
 * 取 16dp 和 AlertDialog 的 `AlertDialogTitlePadding`（title 底部 16dp）对齐 ——
 * 以前 [AppSurfaceDialog] 的调用方各写 12dp，两套壳摆在一起正文起始位置差 4dp。
 */
private val AppDialogTitleGap = 16.dp

/**
 * 全项目弹窗的统一外壳。
 *
 * 之前每个弹窗都自己写一遍 `containerColor = dialogContainer`、`title = { Text(...) }`、
 * 确认按钮、`dismissButton = { TextButton { Text("取消") } }` —— 14 处底色、12 处「取消」
 * 是这么来的。现在只有这里知道弹窗长什么样，各处只给文案和内容。
 *
 * @param title 标题；和 [titleContent] 二选一，[titleContent] 优先（更新弹窗要两行标题）。
 * @param confirmText 确认按钮文案；null 表示没有确认按钮（进度类弹窗）。
 * @param onConfirm 确认回调；和 [confirmText] 同时给才画按钮。
 * @param confirmEnabled 确认按钮是否可点，比如备份弹窗要求至少勾一项。
 * @param destructive 破坏性操作：确认按钮文字用 error 色。
 * @param dismissText 取消按钮文案；null 表示没有取消按钮。
 * @param onDismiss 取消 / 点外部 / 返回键，都走这一个回调。
 */
@Composable
fun AppDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    titleContent: (@Composable () -> Unit)? = null,
    confirmText: String? = null,
    onConfirm: (() -> Unit)? = null,
    confirmEnabled: Boolean = true,
    destructive: Boolean = false,
    dismissText: String? = "取消",
    content: @Composable ColumnScope.() -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.dialogContainer,
        title = when {
            // AlertDialog 会给 title slot 套一层 headlineSmall（Normal），
            // 这里再套一层统一样式把它盖掉，两套壳的标题才是同一个东西
            titleContent != null -> {
                { ProvideTextStyle(AppDialogTitleStyle) { titleContent() } }
            }
            title != null -> {
                { Text(text = title, style = AppDialogTitleStyle) }
            }
            else -> null
        },
        text = { Column(content = content) },
        confirmButton = {
            if (confirmText != null && onConfirm != null) {
                TextButton(onClick = onConfirm, enabled = confirmEnabled) {
                    Text(
                        text = confirmText,
                        // 只有破坏性且可点时才染红；禁用态交给按钮自己压暗
                        color = if (destructive && confirmEnabled) {
                            MaterialTheme.colorScheme.error
                        } else {
                            Color.Unspecified
                        }
                    )
                }
            }
        },
        dismissButton = if (dismissText != null) {
            {
                TextButton(onClick = onDismiss) {
                    Text(dismissText)
                }
            }
        } else {
            null
        }
    )
}

/**
 * 不带标题栏 / 按钮栏的弹窗外壳：[AppDialog] 管不了的那种自己排版按钮的弹窗用它。
 *
 * 之前 PendingRetry / BackupProgress / NoteEdit / BackupManage / RepoSetup / FolderPicker
 * 六个文件各写一遍 `Dialog { Surface(shape, color) { Column { ... } } }`，形状和底色各写一套
 * （RepoSetup 甚至用了另一个圆角）。现在壳只在这里，调用方只给内容。
 *
 * 和 [AppDialog] 的分工：内容能塞进「标题 + 正文 + 左取消右确定」就用 [AppDialog]；
 * 按钮要自己排（放弃放最左、移除备注放最左）或者要全屏 / 改窗口属性，才用这个。
 *
 * 标题请走 [title] / [titleContent]，不要自己在 content 里写 `Text(style = titleMedium)` ——
 * 那会让标题比 [AppDialog] 的弹窗小一档（16sp vs 24sp），两套弹窗摆在一起就不齐了。
 *
 * @param modifier 作用在 Surface 上：控制宽度、全屏、imePadding 这类。
 * @param shape 默认和 [AppDialog] 一致，只有全屏 sheet 这种要覆盖成上圆角。
 * @param properties Dialog 窗口属性：能否点外部 / 返回键关闭、是否用系统默认宽度。
 * @param contentPadding 内容内边距；默认 24dp，紧凑的弹窗传 20dp。
 * @param contentModifier 作用在内部 Column 上：需要 fillMaxSize 或避让系统栏时传。
 * @param windowConfig 需要直接改 Window（全屏 sheet 设 MATCH_PARENT、自画 scrim）时的钩子；
 *   拿到 window 的时机由本组件负责，调用方不用再碰 LocalView / DialogWindowProvider。
 * @param title 纯文本标题；样式与 [AppDialog] 的标题完全一致。
 * @param titleContent 标题需要富文本时用（例如备份管理弹窗在应用名后面挂版本号）；
 *   内部已套好 [AppDialogTitleStyle]，里面直接 `Text(annotated)` 即可，不用再指定 style。
 */
@Composable
fun AppSurfaceDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.extraLarge,
    properties: DialogProperties = DialogProperties(),
    contentPadding: PaddingValues = PaddingValues(24.dp),
    contentModifier: Modifier = Modifier,
    windowConfig: ((Window) -> Unit)? = null,
    title: String? = null,
    titleContent: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismissRequest, properties = properties) {
        if (windowConfig != null) {
            val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
            // 只按 window 做 key：windowConfig 每次重组都是新 lambda，当 key 会让 effect 反复执行
            LaunchedEffect(dialogWindow) {
                dialogWindow?.let(windowConfig)
            }
        }
        Surface(
            modifier = modifier,
            shape = shape,
            color = MaterialTheme.colorScheme.dialogContainer
        ) {
            Column(modifier = contentModifier.padding(contentPadding)) {
                when {
                    titleContent != null -> ProvideTextStyle(AppDialogTitleStyle) {
                        titleContent()
                    }

                    title != null -> Text(text = title, style = AppDialogTitleStyle)
                }
                if (title != null || titleContent != null) {
                    Spacer(modifier = Modifier.height(AppDialogTitleGap))
                }
                content()
            }
        }
    }
}
