package com.cc.libackupnext.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 主用户（user 0）的应用不显示用户标记。 */
const val PRIMARY_USER_ID = 0

fun isNonPrimaryUser(userId: Int): Boolean = userId != PRIMARY_USER_ID

/**
 * 非主用户应用的用户标记，例如「用户:10」。
 *
 * @param compact 紧凑模式，用于贴在应用图标右下角。
 */
@Composable
fun UserBadge(
    userId: Int,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val isDark = isSystemInDarkTheme()
    val background = if (isDark) Color(0xFF2F3D4C) else Color(0xFFD5E4F7)
    val content = if (isDark) Color(0xFFB3C0D3) else Color(0xFF455363)

    Text(
        text = "用户:$userId",
        color = content,
        fontSize = if (compact) 9.sp else 11.sp,
        lineHeight = if (compact) 11.sp else 14.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .background(
                color = background,
                shape = RoundedCornerShape(if (compact) 4.dp else 8.dp)
            )
            .padding(
                horizontal = if (compact) 3.dp else 8.dp,
                vertical = if (compact) 1.dp else 3.dp
            )
    )
}
