package com.cc.libackupnext.ui.theme

import android.app.WallpaperManager
import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
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
 * 全项目唯一的 ColorScheme 生成入口。
 */
@Composable
fun rememberAppColorScheme(
    darkTheme: Boolean,
    seedColor: Color,
    style: PaletteStyle = PaletteStyle.TonalSpot,
    specVersion: ColorSpec.SpecVersion = ColorSpec.SpecVersion.SPEC_2025,
): ColorScheme = remember(seedColor, darkTheme, style, specVersion) {
    dynamicColorScheme(
        seedColor = seedColor,
        isDark = darkTheme,
        style = style,
        specVersion = specVersion,
    )
}
