package com.cc.libackupnext.ui.theme

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import com.cc.libackupnext.data.AppPrefs
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec

/** 配色预设：key 为 "system"（跟随壁纸）或 6 位 RRGGBB。 */
data class ColorSeedOption(val key: String, val label: String, val color: Color?)

val ColorSeedOptions: List<ColorSeedOption> = listOf(
    ColorSeedOption("system", "跟随系统壁纸", null),
    ColorSeedOption("3B5B9B", "品牌蓝", Color(0xFF3B5B9B)),
    ColorSeedOption("3F8F4F", "绿", Color(0xFF3F8F4F)),
    ColorSeedOption("7B5BC4", "紫", Color(0xFF7B5BC4)),
    ColorSeedOption("C25E22", "橙", Color(0xFFC25E22)),
    ColorSeedOption("B5476B", "玫瑰红", Color(0xFFB5476B)),
)

val ColorStyleOptions: List<Pair<PaletteStyle, String>> = listOf(
    PaletteStyle.TonalSpot to "默认",
    PaletteStyle.Neutral to "柔和",
    PaletteStyle.Vibrant to "鲜艳",
    PaletteStyle.Expressive to "表现",
    PaletteStyle.Rainbow to "彩虹",
    PaletteStyle.FruitSalad to "果色",
    PaletteStyle.Monochrome to "单色",
    PaletteStyle.Fidelity to "保真",
    PaletteStyle.Content to "内容",
)

val ColorSpecOptions: List<Pair<ColorSpec.SpecVersion, String>> = listOf(
    ColorSpec.SpecVersion.SPEC_2025 to "SPEC 2025",
    ColorSpec.SpecVersion.SPEC_2021 to "SPEC 2021",
)

/**
 * 外观设置的运行时状态。
 *
 * 用 [mutableStateOf] 是为了让 [AppManagerTheme] 读到变化时立刻重组换色；
 * 持久化仍由 [AppPrefs] 负责。设置页只调这里的 setXxx，不要直接写 AppPrefs。
 */
object ThemeSettings {
    // 不用 `by`：delegate 会生成 setXxx()，和下面的公开方法 JVM 签名冲突
    private val seedKeyState = mutableStateOf(AppPrefs.colorSeed)
    private val styleState = mutableStateOf(
        runCatching { PaletteStyle.valueOf(AppPrefs.colorStyle) }
            .getOrDefault(PaletteStyle.TonalSpot)
    )
    private val specVersionState = mutableStateOf(
        runCatching { ColorSpec.SpecVersion.valueOf(AppPrefs.colorSpec) }
            .getOrDefault(ColorSpec.SpecVersion.SPEC_2025)
    )

    val seedKey: String get() = seedKeyState.value
    val style: PaletteStyle get() = styleState.value
    val specVersion: ColorSpec.SpecVersion get() = specVersionState.value

    fun setSeedKey(value: String) {
        AppPrefs.colorSeed = value
        seedKeyState.value = value
    }

    fun setStyle(value: PaletteStyle) {
        AppPrefs.colorStyle = value.name
        styleState.value = value
    }

    fun setSpecVersion(value: ColorSpec.SpecVersion) {
        AppPrefs.colorSpec = value.name
        specVersionState.value = value
    }

    val seedLabel: String
        get() = ColorSeedOptions.firstOrNull { it.key == seedKey }?.label ?: "自定义"

    val styleLabel: String
        get() = ColorStyleOptions.firstOrNull { it.first == style }?.second ?: style.name

    val specLabel: String
        get() = ColorSpecOptions.firstOrNull { it.first == specVersion }?.second
            ?: specVersion.name
}
