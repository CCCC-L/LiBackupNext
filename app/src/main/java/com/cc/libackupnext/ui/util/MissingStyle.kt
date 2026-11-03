package com.cc.libackupnext.ui.util

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import com.cc.libackupnext.ui.theme.LocalAppColors

/**
 * 「目标已经不存在」的统一表现：内容淡到 45%，标题划线并变灰。
 *
 * 列表行、批量页行、详情页 header 三处共用这一份，别各写一份 0.45f。
 */

/** 不存在时内容的透明度。 */
private const val MISSING_ALPHA = 0.45f

/** 不存在就把这块内容淡下去。 */
fun Modifier.dimmedWhen(missing: Boolean): Modifier =
    alpha(if (missing) MISSING_ALPHA else 1f)

/** 不存在时标题用什么颜色。 */
@Composable
fun missingTitleColor(missing: Boolean): Color =
    if (missing) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface

/** 系统应用的标题色（红）；不是系统应用返回 null，调用方走默认色。 */
@Composable
fun systemAppTitleColor(isSystem: Boolean): Color? =
    if (isSystem) LocalAppColors.current.systemAppTitleColor else null
