package com.cc.libackupnext.ui.theme

import android.app.WallpaperManager
import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import com.materialkolor.dynamiccolor.ColorSpec

/** 系统 / 壁纸都取不到种子色时的兜底：项目原来的品牌蓝。 */
val BrandSeedColor = Color(0xFF3B5B9B)

/**
 * 取一个跟随系统壁纸的种子色。
 * 只在启动时取一次种子色，不监听壁纸变化；换壁纸后需重建 Activity 才会刷新。
 */
fun systemSeedColor(context: Context): Color {
    val systemPrimary = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            dynamicLightColorScheme(context).primary
        } else {
            null
        }
    }.getOrNull()
    if (systemPrimary != null) return systemPrimary
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
        val argb = runCatching {
            WallpaperManager.getInstance(context)
                .getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
                ?.primaryColor
                ?.toArgb()
        }.getOrNull()
        if (argb != null) return Color(argb)
    }
    return BrandSeedColor
}

@Composable
fun rememberSystemSeedColor(): Color {
    val context = LocalContext.current
    return remember(context) { systemSeedColor(context) }
}

/** "system" 走壁纸，其余按 6 位 RRGGBB 解析；解析失败退回品牌蓝。 */
fun resolveSeedColor(key: String, context: Context): Color =
    if (key == "system") {
        systemSeedColor(context)
    } else {
        runCatching { Color(android.graphics.Color.parseColor("#$key")) }
            .getOrDefault(BrandSeedColor)
    }

/**
 * 系统动态配色里的 6 个调色板关键色。
 *
 * 对齐参考 App 的做法：它不是把系统 primary 当成单一种子重新推导整套色板（二次取色
 * 会让 secondary / tertiary / neutral 全跟着 primary 跑），而是把系统的 6 个角色色直接
 * 作为 material-kolor 的 primary / secondary / tertiary / neutral / neutralVariant /
 * error 覆盖色传进去，style / specVersion 仍由用户设置决定。
 */
data class SystemPaletteColors(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val neutral: Color,
    val neutralVariant: Color,
    val error: Color,
)

/**
 * 按深浅模式取系统动态配色并抽出 6 个关键色。
 * 只在启动时取一次，不监听壁纸变化；取不到返回 null，由调用方退回单色种子路径。
 */
fun systemPaletteColors(context: Context, darkTheme: Boolean): SystemPaletteColors? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    return runCatching {
        val scheme = if (darkTheme) {
            dynamicDarkColorScheme(context)
        } else {
            dynamicLightColorScheme(context)
        }
        SystemPaletteColors(
            primary = scheme.primary,
            secondary = scheme.secondary,
            tertiary = scheme.tertiary,
            neutral = scheme.surface,
            neutralVariant = scheme.surfaceVariant,
            error = scheme.error,
        )
    }.getOrNull()
}

/**
 * 参考 App 的 SPEC_2025 兼容处理：2025 规范只支持这 4 种 PaletteStyle，
 * 其余风格自动回退到 SPEC_2021（设置页仍显示用户选中的值）。
 */
private fun effectiveSpecVersion(
    specVersion: ColorSpec.SpecVersion,
    style: PaletteStyle,
): ColorSpec.SpecVersion =
    if (specVersion == ColorSpec.SpecVersion.SPEC_2025 &&
        style != PaletteStyle.TonalSpot &&
        style != PaletteStyle.Neutral &&
        style != PaletteStyle.Vibrant &&
        style != PaletteStyle.Expressive
    ) {
        ColorSpec.SpecVersion.SPEC_2021
    } else {
        specVersion
    }

/**
 * 全项目唯一的 ColorScheme 生成入口。
 *
 * [systemPalette] 非空时走「系统 6 色覆盖」路径（跟随系统壁纸），为空时按单一种子色生成
 * （配色预设）；两条路径都套用参考 App 的 spec 兼容规则。
 */
@Composable
fun rememberAppColorScheme(
    darkTheme: Boolean,
    seedColor: Color,
    style: PaletteStyle = PaletteStyle.TonalSpot,
    specVersion: ColorSpec.SpecVersion = ColorSpec.SpecVersion.SPEC_2025,
    systemPalette: SystemPaletteColors? = null,
): ColorScheme = remember(seedColor, darkTheme, style, specVersion, systemPalette) {
    val spec = effectiveSpecVersion(specVersion, style)
    val generated = if (systemPalette != null) {
        // 6 个调色板全部由系统色指定，seedColor 不参与生成（参考 App 传透明色）。
        dynamicColorScheme(
            seedColor = Color.Transparent,
            isDark = darkTheme,
            primary = systemPalette.primary,
            secondary = systemPalette.secondary,
            tertiary = systemPalette.tertiary,
            neutral = systemPalette.neutral,
            neutralVariant = systemPalette.neutralVariant,
            error = systemPalette.error,
            style = style,
            specVersion = spec,
        )
    } else {
        dynamicColorScheme(
            seedColor = seedColor,
            isDark = darkTheme,
            style = style,
            specVersion = spec,
        )
    }
    // 暗色下把页面底与列表/卡片层分开：页面底（background、surfaceContainer）
    // 用较暗的一层，列表项与卡片（surface）用更亮的 surfaceBright。
    // 亮色保持原生成结果（surface 本身就是卡片色）。
    if (darkTheme) {
        generated.copy(
            background = generated.surfaceContainer,
            surface = generated.surfaceBright,
        )
    } else {
        generated
    }
}
