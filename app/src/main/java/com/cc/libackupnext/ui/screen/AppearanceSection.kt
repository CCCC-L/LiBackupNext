package com.cc.libackupnext.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.cc.libackupnext.ui.component.AppDialog
import com.cc.libackupnext.ui.component.CardGroup
import com.cc.libackupnext.ui.component.SettingsCard
import com.cc.libackupnext.ui.component.SingleChoiceDialog
import com.cc.libackupnext.ui.theme.ColorSeedOptions
import com.cc.libackupnext.ui.theme.ColorSpecOptions
import com.cc.libackupnext.ui.theme.ColorStyleOptions
import com.cc.libackupnext.ui.theme.ThemeSettings

private enum class AppearancePicker { SEED, STYLE, SPEC }

/** 设置页的「外观」分组：配色、色彩风格、色彩标准。改完立刻换色，无需重启。 */
@Composable
fun AppearanceSection() {
    var picker by remember { mutableStateOf<AppearancePicker?>(null) }

    CardGroup(
        "外观",
        { shape ->
            SettingsCard(
                shape = shape,
                title = "配色",
                subtitle = ThemeSettings.seedLabel,
                onClick = { picker = AppearancePicker.SEED }
            )
        },
        { shape ->
            SettingsCard(
                shape = shape,
                title = "色彩风格",
                subtitle = ThemeSettings.styleLabel,
                onClick = { picker = AppearancePicker.STYLE }
            )
        },
        { shape ->
            SettingsCard(
                shape = shape,
                title = "色彩标准",
                subtitle = ThemeSettings.specLabel,
                onClick = { picker = AppearancePicker.SPEC }
            )
        }
    )

    when (picker) {
        AppearancePicker.SEED -> SeedPickerDialog(onDismiss = { picker = null })
        AppearancePicker.STYLE -> SingleChoiceDialog(
            title = "色彩风格",
            options = ColorStyleOptions,
            selected = ThemeSettings.style,
            onSelect = ThemeSettings::setStyle,
            onDismiss = { picker = null }
        )
        AppearancePicker.SPEC -> SingleChoiceDialog(
            title = "色彩标准",
            options = ColorSpecOptions,
            selected = ThemeSettings.specVersion,
            onSelect = ThemeSettings::setSpecVersion,
            onDismiss = { picker = null }
        )
        null -> Unit
    }
}

@Composable
private fun SeedPickerDialog(onDismiss: () -> Unit) {
    AppDialog(
        title = "配色",
        onDismiss = onDismiss,
        confirmText = "取消",
        onConfirm = onDismiss,
        dismissText = null
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 400.dp)
                .verticalScroll(rememberScrollState())
        ) {
            ColorSeedOptions.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            ThemeSettings.setSeedKey(option.key)
                            onDismiss()
                        }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(
                                option.color ?: MaterialTheme.colorScheme.primaryContainer
                            )
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = option.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    RadioButton(
                        selected = ThemeSettings.seedKey == option.key,
                        onClick = null
                    )
                }
            }
        }
    }
}
