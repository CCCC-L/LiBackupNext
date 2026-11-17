package com.cc.libackupnext.ui.component

import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/**
 * 排序 / 筛选菜单里的开关项：一行文字 + 左侧勾选框。
 *
 * 排序菜单里的「倒序」「显示系统应用」「置顶不同版本」三处结构完全一样，抽出来保证一致。
 *
 * **[Checkbox] 的 `onCheckedChange` 必须传 null**：点击由 [DropdownMenuItem] 负责，
 * 如果让 Checkbox 也接手势，它会在菜单项里撑出 48dp 的触摸区、把行高撑高。
 * 注意这和列表行里的用法正好相反 —— 那里必须传非 null，否则行高会塌。
 *
 * @param onClick 切换后由调用方负责关菜单
 */
@Composable
fun CheckboxMenuItem(
    text: String,
    checked: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(text) },
        onClick = onClick,
        leadingIcon = { Checkbox(checked = checked, onCheckedChange = null) }
    )
}
