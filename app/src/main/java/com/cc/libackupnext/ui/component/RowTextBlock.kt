package com.cc.libackupnext.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cc.libackupnext.ui.theme.systemAppTitleColor

/**
 * 列表行里那一段「标题 + 说明 + 脚注」的文字块。
 *
 * 主列表（[AppItemCard]）和批量页（BackupSelectRow）本来各写了一份，结果同一行字
 * 一处是 titleMedium/Bold、行距 2dp，另一处是 bodyMedium/SemiBold、行距 1dp。
 * 现在字号、字重、行距、划线、标题色全在这里定义，两个页面靠 [RowTextDensity] 区分，
 * 不再是两份各自漂移的实现。
 */

/** 文字在行里扮演的角色。 */
enum class RowTextRole { Title, Subtitle, Caption }

/**
 * 三种信息密度。
 *
 * Regular：主列表 / 设置卡片，一行只有三条字，字号可以放大。
 * Compact：批量页这种一屏要塞下几十行的，标题降一档、行距收到 1dp。
 * Dense：详情页的备份记录行 —— 标题是「版本号」这种次级信息，比主列表再小一档且不加粗；
 *   行距和副标题跟 [Regular] 保持一致（副标题是「用户名 · 时间」，不能跟着变小）。
 */
enum class RowTextDensity {
    Regular,
    Compact,
    Dense;

    /** 相邻两行文字之间的缝。 */
    val gap: Dp get() = if (this == Compact) 1.dp else 2.dp
}

/**
 * 这一行代表的目标处于什么状态 —— 标题颜色和删除线都由它推导，调用方不用自己拼
 * `systemAppTitleColor(x) ?: missingTitleColor(y)`。
 */
enum class RowTone {
    /** 正常：标题用 onSurface。 */
    Normal,

    /** 目标已不存在：标题变灰并划线。 */
    Missing,

    /** 系统应用：标题用 error 角色，和用户自己装的应用区分开。 */
    SystemApp,
}

/** 某个角色在某档密度下的字体。 */
@Composable
fun rowTextStyle(role: RowTextRole, density: RowTextDensity): TextStyle {
    val typography = MaterialTheme.typography
    return when (role) {
        RowTextRole.Title -> when (density) {
            RowTextDensity.Regular -> typography.titleMedium
            RowTextDensity.Compact -> typography.bodyMedium
            RowTextDensity.Dense -> typography.labelLarge
        }
        RowTextRole.Subtitle -> when (density) {
            RowTextDensity.Regular, RowTextDensity.Dense -> typography.bodySmall
            RowTextDensity.Compact -> typography.labelSmall
        }
        RowTextRole.Caption -> typography.labelSmall
    }
}

/** 某个角色在某档密度下的字重；null 表示用字体自带的。 */
fun rowTextWeight(role: RowTextRole, density: RowTextDensity): FontWeight? = when (role) {
    RowTextRole.Title -> when (density) {
        RowTextDensity.Regular -> FontWeight.Bold
        RowTextDensity.Compact -> FontWeight.SemiBold
        RowTextDensity.Dense -> FontWeight.Normal
    }
    RowTextRole.Subtitle, RowTextRole.Caption -> null
}

/** 标题颜色：按 [tone] 推导。 */
@Composable
fun rowTitleColor(tone: RowTone): Color = when (tone) {
    RowTone.Normal -> MaterialTheme.colorScheme.onSurface
    RowTone.Missing -> MaterialTheme.colorScheme.onSurfaceVariant
    RowTone.SystemApp -> MaterialTheme.colorScheme.systemAppTitleColor
}

/**
 * @param title 标题，用 [AnnotatedString] 是为了让调用方能在里面插 span（版本号标蓝、备注换行）。
 * @param tone 目标状态，决定标题颜色和是否划线。
 * @param subtitle 说明行；null 就不占位。
 * @param caption 脚注行；null 就不占位。
 * @param titleLeading 标题前面的东西（雪花），不传就没有。
 * @param titleTrailing 标题后面的东西（「当前使用中」标签），不传就没有。
 * @param extra 脚注之后还要加的行（备份数 / 时间 / 备注），行距由本组件统一给。
 */
@Composable
fun RowTextBlock(
    title: AnnotatedString,
    modifier: Modifier = Modifier,
    density: RowTextDensity = RowTextDensity.Regular,
    tone: RowTone = RowTone.Normal,
    subtitle: AnnotatedString? = null,
    caption: AnnotatedString? = null,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    captionColor: Color = MaterialTheme.colorScheme.outline,
    /** 标题额外划线；[RowTone.Missing] 本身就会划线，不用传这个。 */
    titleDecoration: TextDecoration? = null,
    titleMaxLines: Int = 1,
    subtitleMaxLines: Int = Int.MAX_VALUE,
    captionMaxLines: Int = Int.MAX_VALUE,
    titleLeading: (@Composable () -> Unit)? = null,
    titleTrailing: (@Composable () -> Unit)? = null,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(density.gap)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            titleLeading?.invoke()
            Text(
                text = title,
                style = rowTextStyle(RowTextRole.Title, density),
                fontWeight = rowTextWeight(RowTextRole.Title, density),
                color = rowTitleColor(tone),
                maxLines = titleMaxLines,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (tone == RowTone.Missing) {
                    TextDecoration.LineThrough
                } else {
                    titleDecoration
                },
                modifier = Modifier.weight(1f, fill = false)
            )
            titleTrailing?.invoke()
        }
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = rowTextStyle(RowTextRole.Subtitle, density),
                color = subtitleColor,
                maxLines = subtitleMaxLines,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (caption != null) {
            Text(
                text = caption,
                style = rowTextStyle(RowTextRole.Caption, density),
                color = captionColor,
                maxLines = captionMaxLines,
                overflow = TextOverflow.Ellipsis
            )
        }
        extra?.invoke(this)
    }
}

/**
 * [RowTextBlock] 的 `extra` 槽里用的单行文字：样式按角色和密度取，颜色可覆盖。
 *
 * 只固定样式，不固定内容 —— 备份数 / 时间 / 备注这种一行一个说法的，用它比每次手写
 * `style = MaterialTheme.typography.labelSmall` 更不容易跟主行漂开。
 */
@Composable
fun RowTextLine(
    text: AnnotatedString,
    density: RowTextDensity = RowTextDensity.Regular,
    role: RowTextRole = RowTextRole.Caption,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    maxLines: Int = 1,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = rowTextStyle(role, density),
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}
