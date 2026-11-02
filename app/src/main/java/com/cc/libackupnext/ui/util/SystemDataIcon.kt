package com.cc.libackupnext.ui.util

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cc.libackupnext.data.BackupTarget

/** 系统数据的图标：Wi-Fi / 联系人 / 通话记录。 */
private val SystemDataIcons: Map<BackupTarget, ImageVector> = mapOf(
    BackupTarget.WIFI to Icons.Filled.Wifi,
    BackupTarget.CONTACTS to Icons.Filled.Contacts,
    BackupTarget.CALLS to Icons.Filled.Phone
)

/**
 * 固定目标的图标方块：和文件夹图标同一套视觉（圆角底 + 强调色）。
 *
 * 列表行、详情页、批量页都用这一个，尺寸由调用方给。
 */
@Composable
fun SystemDataIconBox(
    target: BackupTarget,
    size: Dp = 48.dp,
    iconSize: Dp = 26.dp,
) {
    VectorIconBox(
        icon = SystemDataIcons[target] ?: Icons.Filled.Settings,
        size = size,
        iconSize = iconSize,
        description = target.displayName
    )
}
