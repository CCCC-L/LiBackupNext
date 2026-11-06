package com.cc.libackupnext.ui.util

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.cc.libackupnext.ui.theme.fieldDisabledLabelColor
import com.cc.libackupnext.ui.theme.fieldDisabledTextColor

/**
 * 密码输入框：标题固定在框内上方不浮动，密文在下一行；按住右侧的小眼睛临时显示明文。
 */
@Composable
fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    disabledContainerColor: Color = containerColor,
    focusedBorderColor: Color = MaterialTheme.colorScheme.primary
) {
    var visible by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall.copy(
            color = if (enabled) colors.onSurface else colors.fieldDisabledTextColor
        ),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = if (visible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        cursorBrush = SolidColor(colors.primary),
        interactionSource = interactionSource,
        decorationBox = { innerTextField ->
            Surface(
                modifier = if (focused && enabled) {
                    Modifier.border(2.dp, focusedBorderColor, shape)
                } else {
                    Modifier
                },
                shape = shape,
                color = if (enabled) containerColor else disabledContainerColor
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (enabled) {
                                colors.onSurfaceVariant
                            } else {
                                colors.fieldDisabledLabelColor
                            }
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Box(contentAlignment = Alignment.CenterStart) {
                            innerTextField()
                        }
                    }

                    if (enabled) {
                        // 按住显示、松开恢复，避免明文一直挂在屏幕上
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onPress = {
                                            visible = true
                                            tryAwaitRelease()
                                            visible = false
                                        }
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (visible) {
                                    Icons.Default.VisibilityOff
                                } else {
                                    Icons.Default.Visibility
                                },
                                contentDescription = "按住显示密码",
                                tint = colors.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    )
}
