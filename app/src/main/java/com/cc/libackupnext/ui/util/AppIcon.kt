package com.cc.libackupnext.ui.util

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 图标加载完成前的占位色，按应用名散列选取，保证同一个应用颜色稳定。 */private val PlaceholderColors = listOf(
    Color(0xFF7E8CE0),
    Color(0xFF4FA9A0),
    Color(0xFFE0915A),
    Color(0xFFB57BD6),
    Color(0xFF6FA8DC),
    Color(0xFFCE7A87),
    Color(0xFF7FA95C),
    Color(0xFF8C8FA6)
)

/**
 * 应用图标（含占位）。图标未就绪时显示首字母色块，避免行内文字左右跳动。
 */
@Composable
fun AppIcon(
    icon: ImageBitmap?,
    name: String,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(14.dp)
) {
    val clipped = modifier.clip(shape)

    if (icon != null) {
        Image(
            bitmap = icon,
            contentDescription = name,
            modifier = clipped.fillMaxSize()
        )
        return
    }

    val color = remember(name) {
        val hash = name.fold(0) { acc, c -> acc * 31 + c.code }
        PlaceholderColors[(hash and Int.MAX_VALUE) % PlaceholderColors.size]
    }
    val letter = remember(name) {
        name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    }

    Box(
        modifier = clipped.background(color),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = letter,
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/** 应用被冻结时显示在应用名前的小雪花。 */
@Composable
fun FrozenSnowflake(modifier: Modifier = Modifier) {
    val isDark = isSystemInDarkTheme()
    Icon(
        imageVector = Icons.Default.AcUnit,
        contentDescription = "已冻结",
        tint = if (isDark) Color(0xFF8FB8E8) else Color(0xFF5A8FD6),
        modifier = modifier.size(14.dp)
    )
}
