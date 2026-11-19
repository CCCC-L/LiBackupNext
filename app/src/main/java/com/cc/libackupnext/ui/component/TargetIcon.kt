package com.cc.libackupnext.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Sms
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
import com.cc.libackupnext.data.target.TargetSpecs
import com.cc.libackupnext.data.AppInfo
import com.cc.libackupnext.ui.util.rememberAppIcon
import com.cc.libackupnext.ui.util.rememberAppIconWithBackup

/** 固定目标的矢量图标，按 spec 的语义名查。加类型时在这里补一行。 */
private val VectorIcons: Map<String, ImageVector> = mapOf(
    "folder" to Icons.Filled.Folder,
    "wifi" to Icons.Filled.Wifi,
    "contacts" to Icons.Filled.Contacts,
    "calls" to Icons.Filled.Phone,
    "sms" to Icons.Filled.Sms
)

/**
 * 备份目标的图标，所有目标共用这一个入口。
 *
 * 用哪种图标由 [TargetSpecs] 的 `iconKey` 决定：应用用 AppInfo 自己的图标，
 * 其余查 [VectorIcons]。加类型时只需在注册表里写 iconKey + 在这张表补一行。
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
    val spec = TargetSpecs.of(target)
    if (spec.iconKey == TargetSpecs.APP_ICON_KEY) {
        AppIcon(
            icon = if (useBackupIcon) {
                rememberAppIconWithBackup(app.packageName, app.userId)
            } else {
                rememberAppIcon(app.packageName, app.userId)
            },
            name = app.name,
            modifier = modifier.size(size)
        )
        return
    }

    val icon = VectorIcons[spec.iconKey]
    if (icon == null) {
        // 注册表写了 iconKey 但 UI 忘了补图标：退化成占位，别让整页崩掉
        VectorIconBox(Icons.Filled.Folder, size, modifier, description = spec.label)
        return
    }

    VectorIconBox(
        icon = icon,
        size = size,
        modifier = modifier,
        description = spec.label
    )
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
            .clip(MaterialTheme.shapes.medium)
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
