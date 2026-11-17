package com.cc.libackupnext.ui.form

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cc.libackupnext.data.RemoteRepo
import com.cc.libackupnext.data.protocol.RepoField
import com.cc.libackupnext.data.protocol.RepoProtocols
import com.cc.libackupnext.ui.theme.fieldDisabledBackground
import com.cc.libackupnext.ui.theme.fieldDisabledLabelColor
import com.cc.libackupnext.ui.theme.fieldDisabledTextColor
import com.cc.libackupnext.ui.theme.warningBackground
import com.cc.libackupnext.ui.theme.warningTextColor

/**
 * 仓库配置表单（本地 / 远程共用一个窗口，不再左右滑动切换）。
 *
 * 协议里就包含「本地」：选本地只显示目录 + rustic 密码；选远程才显示协议声明的
 * 字段、远程路径和测试连接。协议字段由 [com.cc.libackupnext.data.protocol.RepoProtocol.fields]
 * 声明，这里只按类型渲染控件，不认识任何具体协议 —— 新增协议不用改这个文件。
 * 名称默认跟随协议名，切换协议由 [RemoteRepo.switchedTo] 统一处理（用户改过的名称会保留）。
 * 测试连接的结果由调用方内联显示。
 */
@Composable
fun RepoSetupForm(
    repo: RemoteRepo,
    onRepoChange: (RemoteRepo) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    confirmPassword: String,
    onConfirmPasswordChange: (String) -> Unit,
    enabled: Boolean,
    onPickFolder: () -> Unit,
    onBrowseRemotePath: () -> Unit,
    onConnectedAction: (() -> Unit) -> Unit,
    isTesting: Boolean = false,
    onInit: () -> Unit,
    modifier: Modifier = Modifier,
    /** 弹窗自己画底部按钮行时关掉表单内置按钮（编辑页要把「删除」放在最左边）。 */
    showActions: Boolean = true,
    pathTextStyle: TextStyle = MaterialTheme.typography.bodyLarge
) {
    val colors = MaterialTheme.colorScheme
    var showProtocols by remember { mutableStateOf(false) }
    val protocol = repo.protocol
    val isLocal = protocol.isLocal
    val passwordsMatch = password.isNotBlank() && password == confirmPassword

    Column(modifier = modifier.fillMaxWidth()) {
        // 服务协议 + 名称：本地和远程共用这一行
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(modifier = Modifier.weight(1f)) {
                FieldSurface(
                    label = "服务协议",
                    enabled = enabled,
                    onClick = { if (enabled) showProtocols = true }
                ) {
                    Text(
                        text = protocol.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (enabled) colors.onSurface else colors.fieldDisabledTextColor,
                        maxLines = 1
                    )
                }
                DropdownMenu(
                    expanded = showProtocols,
                    onDismissRequest = { showProtocols = false }
                ) {
                    RepoProtocols.all.forEach { candidate ->
                        DropdownMenuItem(
                            text = { Text(candidate.label) },
                            enabled = enabled,
                            onClick = {
                                onRepoChange(repo.switchedTo(candidate))
                                showProtocols = false
                            }
                        )
                    }
                }
            }
            InputField(
                label = "名称",
                value = repo.name,
                onValueChange = { onRepoChange(repo.copy(name = it)) },
                enabled = enabled,
                placeholder = "",
                modifier = Modifier.weight(1f)
            )
        }

        if (isLocal) {
            Spacer(Modifier.height(10.dp))
            FieldSurface(label = "仓库目录", enabled = enabled, onClick = onPickFolder) {
                Text(
                    text = repo.path.ifBlank { "点击选择目录" },
                    style = pathTextStyle,
                    color = when {
                        !enabled -> colors.fieldDisabledTextColor
                        repo.path.isBlank() -> colors.onSurfaceVariant
                        else -> colors.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        } else {
            // 协议自己声明的字段：顺序、占位符、凭据显隐都由协议文件决定
            protocol.fields(repo).forEach { field ->
                Spacer(Modifier.height(10.dp))
                when (field) {
                    is RepoField.Text -> when {
                        field.secret -> RepoPasswordField(
                            password = repo[field.id],
                            onPasswordChange = { onRepoChange(repo.with(field.id, it)) },
                            enabled = enabled,
                            label = field.label
                        )

                        field.multiline -> PemField(
                            label = field.label,
                            placeholder = field.placeholder,
                            value = repo[field.id],
                            onValueChange = { onRepoChange(repo.with(field.id, it)) },
                            enabled = enabled
                        )

                        else -> InputField(
                            label = field.label,
                            value = repo[field.id],
                            onValueChange = { onRepoChange(repo.with(field.id, it)) },
                            enabled = enabled,
                            placeholder = field.placeholder
                        )
                    }

                    is RepoField.Select -> SelectField(
                        field = field,
                        value = repo[field.id],
                        enabled = enabled,
                        onSelect = { onRepoChange(repo.with(field.id, it)) }
                    )

                    is RepoField.Toggle -> ToggleField(
                        field = field,
                        checked = repo.flag(field.id),
                        enabled = enabled,
                        onCheckedChange = { onRepoChange(repo.setFlag(field.id, it)) }
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            // 远程路径只能由全屏文件选择弹窗回填：整块可点，点之前先测连接
            val pathSelectable = enabled && repo.isComplete()
            FieldSurface(
                label = "远程路径",
                enabled = pathSelectable,
                onClick = { onConnectedAction { onBrowseRemotePath() } }
            ) {
                Text(
                    // 远程路径默认就是空；这里只放占位提示，别显示成看起来已填好的路径
                    text = repo.path.ifBlank { "点击选择远程路径" },
                    style = pathTextStyle,
                    color = when {
                        !pathSelectable -> colors.fieldDisabledTextColor
                        repo.path.isBlank() -> colors.onSurfaceVariant
                        else -> colors.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        RepoPasswordField(password, onPasswordChange, enabled)
        Spacer(Modifier.height(10.dp))
        RepoPasswordField(
            confirmPassword,
            onConfirmPasswordChange,
            enabled,
            label = "确认 rustic 仓库密码"
        )
        if (confirmPassword.isNotEmpty() && confirmPassword != password) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "两次输入的密码不一致",
                style = MaterialTheme.typography.bodySmall,
                color = colors.error,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
        Spacer(Modifier.height(6.dp))
        PasswordWarning()

        if (showActions) {
            Spacer(Modifier.height(12.dp))
            if (isLocal) {
                Button(
                    onClick = onInit,
                    enabled = enabled && repo.canInit() && passwordsMatch && !isTesting,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("初始化 rustic 仓库") }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Button(
                        onClick = { onConnectedAction {} },
                        enabled = enabled && repo.isComplete() && !isTesting,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.surfaceContainerHigh,
                            contentColor = colors.onSurface
                        )
                    ) { Text(if (isTesting) "测试中…" else "测试连接") }
                    Button(
                        onClick = onInit,
                        enabled = enabled && repo.canInit() && passwordsMatch && !isTesting,
                        modifier = Modifier.weight(1f)
                    ) { Text("初始化 rustic 仓库") }
                }
            }
        }
    }
}

/** 本地目录、服务协议、各输入框共用的浅色字段块。 */
@Composable
private fun FieldSurface(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick)
                else Modifier
            ),
        shape = MaterialTheme.shapes.medium,
        color = if (enabled) colors.surfaceContainerHigh else colors.fieldDisabledBackground
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (enabled) colors.onSurfaceVariant else colors.fieldDisabledLabelColor
                )
                Spacer(Modifier.height(2.dp))
                content()
            }
            trailing?.invoke()
        }
    }
}

@Composable
private fun InputField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    placeholder: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    val colors = MaterialTheme.colorScheme
    FieldSurface(
        label = label,
        enabled = enabled,
        modifier = modifier,
        trailing = trailing
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = if (enabled) colors.onSurface else colors.fieldDisabledTextColor
            ),
            decorationBox = { inner ->
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.onSurfaceVariant
                    )
                }
                inner()
            }
        )
    }
}

/** 下拉字段（目前只有认证方式用），选项由协议声明。 */
@Composable
private fun SelectField(
    field: RepoField.Select,
    value: String,
    enabled: Boolean,
    onSelect: (String) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    var expanded by remember(field.id) { mutableStateOf(false) }
    // 存的 key 不被支持时和业务侧一样回落到第一项，别显示成空
    val selected = field.options.firstOrNull { it.key == value } ?: field.options.firstOrNull()
    Box(modifier = Modifier.fillMaxWidth()) {
        FieldSurface(
            label = field.label,
            enabled = enabled,
            onClick = { if (enabled) expanded = true }
        ) {
            Text(
                text = selected?.label.orEmpty(),
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) colors.onSurface else colors.fieldDisabledTextColor,
                maxLines = 1
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            field.options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    enabled = enabled,
                    onClick = {
                        onSelect(option.key)
                        expanded = false
                    }
                )
            }
        }
    }
}

/** 带说明的开关行，例如 WebDAV 的「允许不安全的服务」。 */
@Composable
private fun ToggleField(
    field: RepoField.Toggle,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (enabled) colors.surfaceContainerHigh else colors.fieldDisabledBackground
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = field.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (enabled) colors.onSurface else colors.fieldDisabledTextColor
                )
                Text(
                    text = field.hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) colors.onSurfaceVariant else colors.fieldDisabledLabelColor
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled
            )
        }
    }
}

/** 服务密码和 rustic 仓库密码共用同一个密码框组件，只是标签不同。 */
@Composable
private fun RepoPasswordField(
    password: String,
    onPasswordChange: (String) -> Unit,
    enabled: Boolean,
    label: String = "rustic 仓库密码"
) {
    val colors = MaterialTheme.colorScheme
    PasswordField(
        value = password,
        onValueChange = onPasswordChange,
        label = label,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        containerColor = colors.surfaceContainerHigh,
        disabledContainerColor = colors.fieldDisabledBackground,
        focusedBorderColor = colors.primary
    )
}

/** 粘贴 PEM 私钥的多行输入框；值原样保存，service 层下发 inline 配置时才转成单行。 */
@Composable
private fun PemField(
    label: String,
    placeholder: String,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (enabled) colors.surfaceContainerHigh else colors.fieldDisabledBackground
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (enabled) colors.onSurfaceVariant else colors.fieldDisabledLabelColor
            )
            Spacer(Modifier.height(2.dp))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 88.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    color = if (enabled) colors.onSurface else colors.fieldDisabledTextColor
                ),
                decorationBox = { inner ->
                    Box {
                        inner()
                        if (value.isEmpty() && placeholder.isNotEmpty()) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant
                            )
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun PasswordWarning(
    text: String = "请牢记 rustic 仓库密码，丢失后无法恢复备份。"
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.warningBackground
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.warningTextColor,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
