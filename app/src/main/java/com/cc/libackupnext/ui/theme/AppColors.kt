package com.cc.libackupnext.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 项目自定义颜色，统一在 Theme 里管理。
 * 通过 LocalAppColors.current.xxx 读取，暗色模式会自动切换到 DarkAppColors。
 */
data class AppColors(
    val subtitleColor: Color,
    val bottomTextColor: Color,

    val pageBackground: Color,
    val cardBackground: Color,
    val rootCardGrantedBackground: Color,
    val rootCardGrantedTextColor: Color,
    val rootCardGrantedTextVariant: Color,
    val rootGrantedButtonBackground: Color,
    val rootGrantedButtonContent: Color,
    val guideActionButtonColor: Color,

    val accentColor: Color,
    val onAccentColor: Color,
    val fieldBackground: Color,
    val fieldDisabledBackground: Color,
    val fieldDisabledTextColor: Color,
    val fieldDisabledLabelColor: Color,

    val warningTextColor: Color,
    val warningBackground: Color,
)

// ============================ 亮色模式 ============================
val LightAppColors = AppColors(
    subtitleColor = Color(0xFF3B6892),
    bottomTextColor = Color(0xFF4B5057),

    pageBackground = Color(0xFFE8E8F0),
    cardBackground = Color(0xFFE0E2EC),
    rootCardGrantedBackground = Color(0xFFA1CDFF),
    rootCardGrantedTextColor = Color(0xFF1A2A3A),
    rootCardGrantedTextVariant = Color(0xFF2C4055),
    rootGrantedButtonBackground = Color(0xFFCBD2DE),
    rootGrantedButtonContent = Color(0xFF1A2A3A),
    guideActionButtonColor = Color(0xFF34628E),

    accentColor = Color(0xFF3B5B9B),
    onAccentColor = Color(0xFFFFFFFF),
    fieldBackground = Color(0xFFF2F3F7),
    fieldDisabledBackground = Color(0x80F2F3F7),
    fieldDisabledTextColor = Color(0x661C1B1F),
    fieldDisabledLabelColor = Color(0x6649454E),

    warningTextColor = Color(0xFF8A4B00),
    warningBackground = Color(0xFFFFEAC7),

)

// ============================ 暗色模式 ============================
val DarkAppColors = AppColors(
    subtitleColor = Color(0xFF8AB4E8),
    bottomTextColor = Color(0xFF9BA1A9),

    pageBackground = Color(0xFF121318),
    cardBackground = Color(0xFF1E1F24),
    rootCardGrantedBackground = Color(0xFF255581),
    rootCardGrantedTextColor = Color(0xFFDCE8F5),
    rootCardGrantedTextVariant = Color(0xFFB8CADD),
    rootGrantedButtonBackground = Color(0xFF323944),
    rootGrantedButtonContent = Color(0xFF5B626D),
    guideActionButtonColor = Color(0xFF9ECAFC),

    accentColor = Color(0xFF9ECAFC),
    onAccentColor = Color(0xFF0F1A2A),
    fieldBackground = Color(0xFF2A2B30),
    fieldDisabledBackground = Color(0x802A2B30),
    fieldDisabledTextColor = Color(0x66E5E1E6),
    fieldDisabledLabelColor = Color(0x66CAC4CF),

    // 暗色模式：深棕橙底 + 亮橙字
    warningTextColor = Color(0xFFFFC178),
    warningBackground = Color(0xFF3A2A15),

)

// 默认值，防止在 Theme 之外使用时崩溃
val LocalAppColors = staticCompositionLocalOf { LightAppColors }