package com.cc.libackupnext.ui.dialog

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cc.libackupnext.data.ReleaseBlock
import com.cc.libackupnext.data.ReleaseInfo

/** 行内标记：**粗体**、`代码`。其余原样显示，不处理链接。 */
private val INLINE = Regex("""\*\*(.+?)\*\*|`([^`]+)`""")

/** 发现新版本：按 Markdown 块结构排版 Release 说明，只提供「取消」和「浏览器下载」。 */
@Composable
fun UpdateDialog(
    info: ReleaseInfo,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val maxContentHeight = LocalConfiguration.current.screenHeightDp.dp * 0.6f
    // 浅色跟项目页面背景统一（各页面都是硬编码的 #EAEEF7），暗色沿用 AlertDialog 默认不动
    val containerColor = if (isSystemInDarkTheme()) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        Color(0xFFEAEEF7)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = containerColor,
        title = {
            Column {
                Text(
                    text = "发现新版本",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = info.version,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = maxContentHeight)
                    .verticalScroll(rememberScrollState())
            ) {
                if (info.notes.isEmpty()) {
                    Text(
                        text = "暂无更新说明",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    info.notes.forEachIndexed { index, block ->
                        ReleaseBlockView(block, first = index == 0)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    // 没有浏览器/链接异常时也不该把应用弄崩
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(info.downloadUrl))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
            ) {
                Text("浏览器下载")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun ReleaseBlockView(block: ReleaseBlock, first: Boolean) {
    when (block) {
        is ReleaseBlock.Heading -> {
            // level 原样保留 1..6，每一级字号 / 字重 / 上下间距都不同
            val style = when (block.level) {
                1 -> MaterialTheme.typography.headlineSmall
                2 -> MaterialTheme.typography.titleLarge
                3 -> MaterialTheme.typography.titleMedium
                4 -> MaterialTheme.typography.titleSmall
                5 -> MaterialTheme.typography.bodyLarge
                else -> MaterialTheme.typography.bodyMedium
            }
            // 第一个块紧接副标题，弹窗自身已有 title/text 间距，别再叠一层
            val top = if (first) 0.dp else when (block.level) {
                1 -> 20.dp
                2 -> 16.dp
                3 -> 12.dp
                4 -> 10.dp
                else -> 8.dp
            }
            Text(
                text = inlineText(block.text),
                // 中文字体常把 Medium(500) 映射成粗体，索性用常规字重，
                // 层级完全靠字号 / 间距 / 颜色区分
                style = style.copy(fontWeight = FontWeight.Normal),
                color = if (block.level >= 5) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.padding(top = top, bottom = 6.dp)
            )
        }

        is ReleaseBlock.Bullet -> {
            // 圆点单独一列，换行文字自动对齐到文字列 = 悬挂缩进
            Row(
                modifier = Modifier.padding(
                    start = (block.depth * 16).dp,
                    bottom = 6.dp
                )
            ) {
                Text(
                    text = "•",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    text = inlineText(block.text),
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        is ReleaseBlock.Paragraph -> Text(
            text = inlineText(block.text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 6.dp)
        )
    }
}

/** 只转两种行内标记：**粗体**、`等宽灰字`；其它（含链接）原样输出。 */
@Composable
private fun inlineText(raw: String): AnnotatedString {
    val codeColor = MaterialTheme.colorScheme.onSurfaceVariant
    return buildAnnotatedString {
        var cursor = 0
        INLINE.findAll(raw).forEach { match ->
            if (match.range.first > cursor) append(raw.substring(cursor, match.range.first))
            val bold = match.groupValues[1]
            if (bold.isNotEmpty()) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold) }
            } else {
                withStyle(
                    SpanStyle(fontFamily = FontFamily.Monospace, color = codeColor)
                ) {
                    append(match.groupValues[2])
                }
            }
            cursor = match.range.last + 1
        }
        if (cursor < raw.length) append(raw.substring(cursor))
    }
}
