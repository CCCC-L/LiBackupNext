package com.cc.libackupnext.ui.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 「转圈 + 一句话」的横排加载提示。
 *
 * 只统一「图标 + 间距 + 居中对齐」这三件重复的事；文字是 slot，字号和颜色由调用方按场合给
 * （弹窗里通常比页面里小一档）。竖排的加载态（图标在上、文字在下）不归这里管。
 *
 * @param indicatorSize 转圈直径；弹窗里塞在窄行时传 18dp，页面级用默认 24dp
 * @param spacing 转圈和文字之间的距离，跟着 indicatorSize 一起缩
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LoadingRow(
    modifier: Modifier = Modifier,
    indicatorSize: Dp = 24.dp,
    spacing: Dp = 12.dp,
    content: @Composable () -> Unit,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        LoadingIndicator(modifier = Modifier.size(indicatorSize))
        Spacer(modifier = Modifier.width(spacing))
        content()
    }
}
