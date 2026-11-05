package com.cc.libackupnext.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.ShapeDefaults
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val ExpressiveShapes = Shapes(
    extraSmall = ShapeDefaults.ExtraSmall,                   // 4dp
    small = ShapeDefaults.Small,                             // 8dp
    medium = ShapeDefaults.Medium,                           // 12dp
    large = ShapeDefaults.Large,                             // 16dp
    extraLarge = ShapeDefaults.ExtraLarge,                   // 28dp
    largeIncreased = ShapeDefaults.LargeIncreased,           // 20dp
    extraLargeIncreased = ShapeDefaults.ExtraLargeIncreased, // 32dp
    extraExtraLarge = ShapeDefaults.ExtraExtraLarge,         // 48dp
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppManagerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** 临时 A/B 开关：true 回到系统壁纸配色（路线 A），false 用 MaterialKolor 生成。 */
    useSystemScheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    // 读 ThemeSettings 的 mutableStateOf：设置页改配置后这里立刻重组换色
    val seedKey = ThemeSettings.seedKey
    val style = ThemeSettings.style
    val specVersion = ThemeSettings.specVersion
    val seedColor = remember(seedKey, context) { resolveSeedColor(seedKey, context) }

    val colorScheme = if (useSystemScheme) {
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        rememberAppColorScheme(
            darkTheme = darkTheme,
            seedColor = seedColor,
            style = style,
            specVersion = specVersion,
        )
    }

    // 组件全部直接读 MaterialTheme.colorScheme；语义别名见 ColorRoles.kt
    // Expressive 主题入口：默认启用 MotionScheme.expressive()，后续换 Expressive 组件时不用再动主题。
    // 配色由 rememberAppColorScheme / 系统动态取色决定，形状由 ExpressiveShapes 统一提供。
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        shapes = ExpressiveShapes,
        content = content
    )
}
