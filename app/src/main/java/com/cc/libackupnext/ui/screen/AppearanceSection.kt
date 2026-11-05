package com.cc.libackupnext.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.cc.libackupnext.ui.theme.ColorSeedOptions
import com.cc.libackupnext.ui.theme.ColorSpecOptions
import com.cc.libackupnext.ui.theme.ColorStyleOptions
import com.cc.libackupnext.ui.theme.ThemeSettings
import com.cc.libackupnext.ui.theme.dialogContainer

private enum class AppearancePicker { SEED, STYLE, SPEC }

/** 设置页的「外观」分组：配色、色彩风格、色彩标准。改完立刻换色，无需重启。 */
@Composable
fun AppearanceSection() {
    var picker by remember { mutableStateOf<AppearancePicker?>(null) }

    SectionTitle("外观")

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column {
            OptionRow("配色", ThemeSettings.seedLabel) { picker = AppearancePicker.SEED }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            OptionRow("色彩风格", ThemeSettings.styleLabel) { picker = AppearancePicker.STYLE }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            OptionRow("色彩标准", ThemeSettings.specLabel) { picker = AppearancePicker.SPEC }
        }
    }

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
private fun OptionRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SeedPickerDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.dialogContainer,
        title = { Text("配色") },
        text = {
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
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun <T> SingleChoiceDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.dialogContainer,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                options.forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelect(value)
                                onDismiss()
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        RadioButton(selected = selected == value, onClick = null)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
