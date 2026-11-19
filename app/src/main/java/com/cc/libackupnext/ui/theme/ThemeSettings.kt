package com.cc.libackupnext.ui.theme

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import com.cc.libackupnext.data.prefs.ViewPrefs
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
 * 持久化由 [ViewPrefs] 负责。设置页只调这里的 setXxx，不要直接写存储。
 *
 * 保持 object 而不是做成实例：它是**应用级单例状态**（主题只有一个），
 * 依赖在 [com.cc.libackupnext.App.onCreate] 里注入一次。
 * 做成实例就得给每个读主题的地方配一个 CompositionLocal，
 * 而它们本来就该看到同一份状态 —— 收益抵不过复杂度。
 */
object ThemeSettings {

    private lateinit var prefs: ViewPrefs

    /** 由 Application 在启动时注入；重复调用是幂等的。 */
    fun install(prefs: ViewPrefs) {
        this.prefs = prefs
        seedKeyState.value = prefs.colorSeed
        styleState.value = runCatching { PaletteStyle.valueOf(prefs.colorStyle) }
            .getOrDefault(PaletteStyle.TonalSpot)
        specVersionState.value = runCatching { ColorSpec.SpecVersion.valueOf(prefs.colorSpec) }
            .getOrDefault(ColorSpec.SpecVersion.SPEC_2025)
    }

    // 不用 `by`：delegate 会生成 setXxx()，和下面的公开方法 JVM 签名冲突
    private val seedKeyState = mutableStateOf("system")
    private val styleState = mutableStateOf(PaletteStyle.TonalSpot)
    private val specVersionState = mutableStateOf(ColorSpec.SpecVersion.SPEC_2025)

    val seedKey: String get() = seedKeyState.value
    val style: PaletteStyle get() = styleState.value
    val specVersion: ColorSpec.SpecVersion get() = specVersionState.value

    fun setSeedKey(value: String) {
        prefs.colorSeed = value
        seedKeyState.value = value
    }

    fun setStyle(value: PaletteStyle) {
        prefs.colorStyle = value.name
        styleState.value = value
    }

    fun setSpecVersion(value: ColorSpec.SpecVersion) {
        prefs.colorSpec = value.name
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
