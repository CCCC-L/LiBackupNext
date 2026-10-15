package com.cc.libackupnext.ui.dialog

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** 应用操作菜单项。 */
enum class AppAction {
    LAUNCH,
    FORCE_STOP,
    FREEZE,
    UNFREEZE,
    APP_SETTINGS,
    CLEAR_DATA,
    UNINSTALL;

    fun title(): String = when (this) {
        LAUNCH -> "启动应用"
        FORCE_STOP -> "强制停止"
        FREEZE -> "冻结应用"
        UNFREEZE -> "解冻应用"
        APP_SETTINGS -> "系统设置"
        CLEAR_DATA -> "清空数据"
        UNINSTALL -> "卸载应用"
    }
}

/**
 * 应用操作菜单，样式与顶部栏的用户 / 排序菜单保持一致。
 *
 * @param frozen 应用是否已被冻结，决定显示「冻结应用」还是「解冻应用」。
 */
@Composable
fun AppOptionsMenu(
    expanded: Boolean,
    frozen: Boolean,
    onDismiss: () -> Unit,
    onAction: (AppAction) -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val menuColor = if (isDark) Color(0xFF2B2D31) else Color(0xFFF2F3F7)
    val dangerColor = MaterialTheme.colorScheme.error

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
        containerColor = menuColor
    ) {
        DropdownMenuItem(
            text = { Text(AppAction.LAUNCH.title()) },
            onClick = { onAction(AppAction.LAUNCH) }
        )
        DropdownMenuItem(
            text = { Text(AppAction.FORCE_STOP.title()) },
            onClick = { onAction(AppAction.FORCE_STOP) }
        )
        if (frozen) {
            DropdownMenuItem(
                text = { Text(AppAction.UNFREEZE.title()) },
                onClick = { onAction(AppAction.UNFREEZE) }
            )
        } else {
            DropdownMenuItem(
                text = { Text(AppAction.FREEZE.title()) },
                onClick = { onAction(AppAction.FREEZE) }
            )
        }
        DropdownMenuItem(
            text = { Text(AppAction.APP_SETTINGS.title()) },
            onClick = { onAction(AppAction.APP_SETTINGS) }
        )

        DropdownMenuItem(
            text = { Text(AppAction.CLEAR_DATA.title(), color = dangerColor) },
            onClick = { onAction(AppAction.CLEAR_DATA) }
        )
        DropdownMenuItem(
            text = { Text(AppAction.UNINSTALL.title(), color = dangerColor) },
            onClick = { onAction(AppAction.UNINSTALL) }
        )
    }
}
