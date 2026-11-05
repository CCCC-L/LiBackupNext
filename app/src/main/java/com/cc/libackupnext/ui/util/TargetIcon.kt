package com.cc.libackupnext.ui.util

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cc.libackupnext.data.BackupTarget
import com.cc.libackupnext.entity.AppInfo

/** 固定目标的矢量图标：Wi-Fi / 联系人 / 通话记录。 */
private val SystemDataIcons: Map<BackupTarget, ImageVector> = mapOf(
    BackupTarget.WIFI to Icons.Filled.Wifi,
    BackupTarget.CONTACTS to Icons.Filled.Contacts,
    BackupTarget.CALLS to Icons.Filled.Phone
)

/**
 * 备份目标的图标，五种目标共用这一个入口。
 *
 * @param size 方块边长；矢量图标按它等比缩到 0.54（48 → 26、40 → 22）
 * @param useBackupIcon 应用图标拿不到时要不要去仓库里找；批量页用不到，传 false 省一次查询
 */
@Composable
fun TargetIconBox(
    target: BackupTarget,
    app: AppInfo,
    size: Dp = 48.dp,
    modifier: Modifier = Modifier,
    useBackupIcon: Boolean = true,
) {
    when (target) {
        BackupTarget.APP -> AppIcon(
            icon = if (useBackupIcon) {
                rememberAppIconWithBackup(app.packageName, app.userId)
            } else {
                rememberAppIcon(app.packageName, app.userId)
            },
            name = app.name,
            modifier = modifier.size(size)
        )

        BackupTarget.DIR -> VectorIconBox(Icons.Filled.Folder, size, modifier)

        BackupTarget.WIFI, BackupTarget.CONTACTS,
        BackupTarget.CALLS -> VectorIconBox(
            icon = SystemDataIcons.getValue(target),
            size = size,
            modifier = modifier,
            description = target.displayName
        )
    }
}

/**
 * 圆角方块 + 强调色底 + 一个矢量图标。
 *
 * 文件夹和系统数据都用它 —— 颜色或圆角一对不上就很明显。
 */
@Composable
private fun VectorIconBox(
    icon: ImageVector,
    size: Dp = 48.dp,
    modifier: Modifier = Modifier,
    description: String? = null,
    iconSize: Dp = size * 0.54f,
) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(accent.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = accent,
            modifier = Modifier.size(iconSize)
        )
    }
}
