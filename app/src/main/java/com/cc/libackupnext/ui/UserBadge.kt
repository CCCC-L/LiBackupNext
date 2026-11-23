package com.cc.libackupnext.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cc.libackupnext.data.MediaPath

/** 主用户（user 0）的应用不显示用户标记。 */
const val PRIMARY_USER_ID = MediaPath.PRIMARY_USER_ID

fun isNonPrimaryUser(userId: Int): Boolean = userId != PRIMARY_USER_ID

/**
 * 非主用户目标的用户标记，例如「用户10」。
 *
 * 列表行标在行尾，详情页标在图标下方，两处同一份样式 —— 同一个事实不该有两种长相。
 */
@Composable
fun UserBadge(
    userId: Int,
    modifier: Modifier = Modifier
) {
    Text(
        text = "用户$userId",
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.small
            )
            .padding(horizontal = 3.dp, vertical = 2.dp)
    )
}
