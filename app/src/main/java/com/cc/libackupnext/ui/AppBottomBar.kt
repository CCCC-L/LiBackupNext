package com.cc.libackupnext.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** 主页面的两个页签。 */
enum class MainTab(val label: String, val icon: ImageVector) {
    APPS("应用", Icons.Filled.Apps),
    FILES("文件", Icons.Filled.Folder)
}

/**
 * M3E 风格底栏：悬浮的圆角胶囊，选中项展开成「图标 + 文字」的药丸。
 */
@Composable
fun AppBottomBar(
    selected: MainTab,
    onSelect: (MainTab) -> Unit,
    containerColor: Color,
    modifier: Modifier = Modifier
) {
    val items = MainTab.entries
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 72.dp, top = 8.dp, end = 72.dp, bottom = 25.dp),
        shape = RoundedCornerShape(20.dp),
        color = containerColor,
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { item ->
                val active = item == selected
                val tint = if (active) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (active) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
                        )
                        .clickable { if (!active) onSelect(item) }
                        .padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.label,
                        tint = tint,
                        modifier = Modifier.size(22.dp)
                    )
                    if (active) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = tint
                        )
                    }
                }
            }
        }
    }
}
