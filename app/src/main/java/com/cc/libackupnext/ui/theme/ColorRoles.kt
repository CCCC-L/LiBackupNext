package com.cc.libackupnext.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

/**
 * 项目里「语义名 ≠ M3 角色名」的颜色，直接挂在 [ColorScheme] 上。
 */

/** 版本号 / 副标题强调色，用主色。 */
val ColorScheme.heighLightColor: Color get() = primary

/** 系统应用的标题色：用 error 角色，和用户自己装的应用区分开。 */
val ColorScheme.systemAppTitleColor: Color get() = error

/** 批量页多选时，被选中的那一行的底色。 */
val ColorScheme.rowSelectedBackground: Color get() = secondaryContainer

val ColorScheme.rootCardGrantedBackground: Color get() = primaryContainer
val ColorScheme.rootCardGrantedTextColor: Color get() = onPrimaryContainer
val ColorScheme.rootCardGrantedTextVariant: Color
    get() = onPrimaryContainer.copy(alpha = 0.75f)
val ColorScheme.rootGrantedButtonBackground: Color get() = secondaryContainer
val ColorScheme.rootGrantedButtonContent: Color
    get() = onSurface.copy(alpha = 0.38f)

val ColorScheme.warningBackground: Color get() = tertiaryContainer
val ColorScheme.warningTextColor: Color get() = onTertiaryContainer

val ColorScheme.fieldDisabledBackground: Color
    get() = surfaceContainerHigh.copy(alpha = 0.5f)
val ColorScheme.fieldDisabledTextColor: Color
    get() = onSurface.copy(alpha = 0.38f)
val ColorScheme.fieldDisabledLabelColor: Color
    get() = onSurfaceVariant.copy(alpha = 0.38f)

/** 所有弹窗统一用和页面 Scaffold 相同的底色，避免不同弹窗各用一套。 */
val ColorScheme.dialogContainer: Color get() = surfaceContainerLow
