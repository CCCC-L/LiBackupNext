package com.cc.libackupnext.ui.util

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cc.libackupnext.ui.theme.LocalAppColors

/**
 * 文件夹占位图标。
 *
 * 文件列表页和文件详情页共用同一份样式 —— 详情页的图标由列表页传下来，
 * 免得两边各画一套、颜色或圆角对不上。
 */
@Composable
fun FolderIconBox(size: Dp = 48.dp, iconSize: Dp = 26.dp) {
    val accent = LocalAppColors.current.accentColor
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(accent.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Filled.Folder,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(iconSize)
        )
    }
}
