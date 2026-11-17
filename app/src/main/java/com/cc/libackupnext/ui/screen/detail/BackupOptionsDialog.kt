package com.cc.libackupnext.ui.screen.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import com.cc.libackupnext.data.BackupGroup
import com.cc.libackupnext.data.BackupTarget
import com.cc.libackupnext.ui.component.AppDialog

/**
 * 备份选项弹窗。
 *
 * 应用：apk / user / data 三选
 * 短信：短信 / 彩信 两选
 * 文件夹和其余系统数据没有可选项，只有一句确认。
 */
@Composable
fun BackupOptionsDialog(
    target: BackupTarget,
    dirPath: String,
    checked: Set<BackupGroup>,
    onToggle: (BackupGroup, Boolean) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AppDialog(
        title = "备份",
        onDismiss = onDismiss,
        confirmText = "备份",
        onConfirm = onConfirm,
        // 没有可选项的目标永远可以备份；应用至少要勾一项
        confirmEnabled = target.kinds.size == 1 || checked.isNotEmpty()
    ) {
        if (target.kinds.size == 1) {
            // 整个全备，没有可选项
            Text(
                text = if (target == BackupTarget.DIR) {
                    "确定备份「$dirPath」么？"
                } else {
                    "确定备份「${target.displayName}」么？"
                },
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            // 勾哪几项由 target.kinds 决定，不在这里写死
            target.kinds.forEach { group ->
                BackupOptionRow(
                    label = group.optionLabel,
                    checked = group in checked,
                    onCheckedChange = { onToggle(group, it) }
                )
            }
        }
    }
}

/** 一个勾选项：整行可点，勾选框用 M3 的标准 Checkbox（和备份管理弹窗同一个组件）。 */
@Composable
private fun BackupOptionRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            // 点文字 / 空白处也能切换；点方框本身由 Checkbox 自己处理，不会重复触发
            .clickable(role = Role.Checkbox) { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        // onCheckedChange 必须非 null：M3 只在非 null 时才套 minimumInteractiveComponentSize，
        // 传 null 会让 Checkbox 缩成 18dp，整行行高塌掉、几行贴在一起
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}
