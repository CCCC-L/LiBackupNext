package com.cc.libackupnext.ui.util

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cc.libackupnext.ui.theme.LocalAppColors

/**
 * 仓库配置表单：目录选择 + 密码输入 + 「请牢记密码」提示。
 *
 * 引导页和设置页的「选择并初始化仓库」用的是同一份实现，改一处两边都变。
 *
 * @param enabled 初始化过程中传 false，整块变灰不可点
 */
@Composable
fun RepoSetupForm(
    path: String,
    onPickFolder: () -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    placeholder: String = "点击选择目录",
    pathTextStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    showPasswordWarning: Boolean = true
) {
    val appColors = LocalAppColors.current

    Column(modifier = modifier.fillMaxWidth()) {
        // 地址栏：点击整块触发文件夹选择
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onPickFolder),
            shape = RoundedCornerShape(12.dp),
            color = if (enabled) appColors.fieldBackground else appColors.fieldDisabledBackground
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(
                    text = "仓库目录",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        appColors.fieldDisabledLabelColor
                    }
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = path.ifBlank { placeholder },
                    style = pathTextStyle,
                    color = when {
                        !enabled -> appColors.fieldDisabledTextColor
                        path.isBlank() -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 默认打码，按住眼睛看明文
        PasswordField(
            value = password,
            onValueChange = onPasswordChange,
            label = "rustic 密码",
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            containerColor = appColors.fieldBackground,
            disabledContainerColor = appColors.fieldDisabledBackground,
            focusedBorderColor = appColors.accentColor
        )

        if (showPasswordWarning) {
            Spacer(modifier = Modifier.height(6.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = appColors.warningBackground
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "请牢记仓库密码，否则无法恢复仓库备份。",
                        style = MaterialTheme.typography.bodySmall,
                        color = appColors.warningTextColor,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}
