package com.cc.libackupnext.ui.dialog

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** 详情页操作菜单的菜单项。不同目标（应用 / 文件夹 / 以后的 Wi-Fi 等）用不同的子集。 */
enum class ItemAction {
    /** 应用 */
    LAUNCH,
    FORCE_STOP,
    FREEZE,
    UNFREEZE,
    APP_SETTINGS,
    CLEAR_DATA,
    UNINSTALL,

    /**目录 */
    OPEN_DIR,
    DELETE_DIR,

    /** Wi-Fi */
    WIFI_ON,
    WIFI_OFF,
    OPEN_WIFI_SETTINGS,

    /** 联系人 */
    OPEN_CONTACTS,
    /** 通话记录 */
    OPEN_CALL_LOG;

    fun title(): String = when (this) {
        LAUNCH -> "启动应用"
        FORCE_STOP -> "强制停止"
        FREEZE -> "冻结应用"
        UNFREEZE -> "解冻应用"
        APP_SETTINGS -> "系统设置"
        CLEAR_DATA -> "清空数据"
        UNINSTALL -> "卸载应用"
        OPEN_DIR -> "打开文件夹"
        DELETE_DIR -> "删除文件夹"
        WIFI_ON -> "打开 Wi-Fi"
        WIFI_OFF -> "关闭 Wi-Fi"
        OPEN_WIFI_SETTINGS -> "Wi-Fi 设置"
        OPEN_CONTACTS -> "打开联系人"
        OPEN_CALL_LOG -> "打开通话记录"
    }
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
        shape = RoundedCornerShape(16.dp),
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
