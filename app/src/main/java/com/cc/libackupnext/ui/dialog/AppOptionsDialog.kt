package com.cc.libackupnext.ui.dialog

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.cc.libackupnext.data.target.ItemAction

/**
 * 菜单项文案。
 *
 * 动作本身是领域语义，定义在 `data/target/ItemAction.kt`；
 * 这里只负责把它翻成中文 —— 换语言 / 改措辞不用碰数据层。
 */
fun ItemAction.title(): String = when (this) {
    ItemAction.LAUNCH -> "启动应用"
    ItemAction.FORCE_STOP -> "强制停止"
    ItemAction.FREEZE -> "冻结应用"
    ItemAction.UNFREEZE -> "解冻应用"
    ItemAction.APP_SETTINGS -> "系统设置"
    ItemAction.CLEAR_DATA -> "清空数据"
    ItemAction.UNINSTALL -> "卸载应用"
    ItemAction.OPEN_DIR -> "打开文件夹"
    ItemAction.DELETE_DIR -> "删除文件夹"
    ItemAction.WIFI_ON -> "打开 Wi-Fi"
    ItemAction.WIFI_OFF -> "关闭 Wi-Fi"
    ItemAction.OPEN_WIFI_SETTINGS -> "Wi-Fi 设置"
    ItemAction.OPEN_CONTACTS -> "打开联系人"
    ItemAction.OPEN_CALL_LOG -> "打开通话记录"
    ItemAction.OPEN_SMS -> "打开短彩信"
}

/** 菜单里标红的危险动作。加新动作时想一下要不要放进来。 */
private val DANGER_ACTIONS = setOf(
    ItemAction.CLEAR_DATA,
    ItemAction.UNINSTALL,
    ItemAction.DELETE_DIR
)

/** 这个动作是不是危险动作（菜单里标红）。 */
val ItemAction.isDanger: Boolean get() = this in DANGER_ACTIONS

/**
 * 操作菜单，样式与顶部栏的用户 / 排序菜单保持一致。
 *
 * 只负责渲染 [actions] —— 哪些动作属于哪个目标由 [DetailTarget] 决定，这里不做判断。
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
                        text = action.title(),
                        color = if (action.isDanger) dangerColor else Color.Unspecified
                    )
                },
                onClick = { onAction(action) }
            )
        }
    }
}
