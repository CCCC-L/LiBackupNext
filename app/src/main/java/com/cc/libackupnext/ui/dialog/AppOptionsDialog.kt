package com.cc.libackupnext.ui.dialog

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.cc.libackupnext.data.target.ItemAction

/**
 * 操作菜单，样式与顶部栏的用户 / 排序菜单保持一致。
 *
 * 只负责渲染 [actions] —— 哪些动作属于哪个目标由 [DetailTarget] 决定，
 */
@Composable
fun AppOptionsMenu(
    expanded: Boolean,
    actions: List<ItemAction>,
    onDismiss: () -> Unit,
    onAction: (ItemAction) -> Unit,
) {
    val menuColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val dangerColor = MaterialTheme.colorScheme.error

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        containerColor = menuColor
    ) {
        actions.forEach { action ->
            DropdownMenuItem(
                text = {
                    Text(
                        text = action.title,
                        color = if (action.destructive) dangerColor else Color.Unspecified
                    )
                },
                onClick = { onAction(action) }
            )
        }
    }
}
