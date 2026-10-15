package com.cc.libackupnext.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cc.libackupnext.ui.theme.LocalAppColors
import com.cc.libackupnext.ui.util.RepoSetupForm

@Composable
fun WelcomeScreen(
    hasRoot: Boolean,
    isRequesting: Boolean,
    isInitializing: Boolean,
    isRepoReady: Boolean,
    rusticPath: String,
    onRusticPathChange: (String) -> Unit,
    rusticPassword: String,
    onRusticPasswordChange: (String) -> Unit,
    onRequestRoot: () -> Unit,
    onInitRusticRepo: () -> Unit,
    onEnterApp: () -> Unit,
    initError: String?,
    onDismissError: () -> Unit
) {
    val context = LocalContext.current
    val appColors = LocalAppColors.current
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp

    // 从主题里读取所有颜色
    val pageBackground = appColors.pageBackground
    val cardBackground = appColors.cardBackground
    val rootCardBackground =
        if (hasRoot) appColors.rootCardGrantedBackground else cardBackground
    val onRootCardColor =
        if (hasRoot) appColors.rootCardGrantedTextColor else MaterialTheme.colorScheme.onSurface
    val onRootCardVariant =
        if (hasRoot) appColors.rootCardGrantedTextVariant else MaterialTheme.colorScheme.onSurfaceVariant
    val accentColor = appColors.accentColor
    val onAccentColor = appColors.onAccentColor

    // Rustic 区域是否可用：仅在已获得 Root 后
    val rusticEnabled = hasRoot

    // 目录为空、密码为空、或者已经初始化成功时都不允许再点初始化
    val canInitRepo = rusticEnabled &&
            rusticPath.isNotBlank() &&
            rusticPassword.isNotBlank() &&
            !isInitializing &&
            !isRepoReady

    // 文件夹选择器
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // 某些 provider 不支持持久化权限，忽略即可
            }
            onRusticPathChange(uri.toString())
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = pageBackground
    ) {
        // 外层：可滚动。内容不足一屏时不滚动，超过一屏时可滚动。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // 内层：至少一屏高，用 SpaceEvenly 均分剩余空间，保证竖屏大屏时视觉依然舒展
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = screenHeight)
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceEvenly
            ) {
                Text(
                    text = "引导",
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                // Root 权限
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = rootCardBackground
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Root 权限",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = onRootCardColor
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = when {
                                    hasRoot -> "已获取 Root 权限"
                                    isRequesting -> "正在请求 Root 权限..."
                                    else -> "获取 Root 权限"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = onRootCardVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Button(
                            onClick = onRequestRoot,
                            enabled = !hasRoot && !isRequesting,
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = accentColor,
                                contentColor = onAccentColor,
                                disabledContainerColor = appColors.rootGrantedButtonBackground,
                                disabledContentColor = appColors.rootGrantedButtonContent
                            ),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
                        ) {
                            if (isRequesting) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .width(18.dp)
                                        .height(18.dp),
                                    color = onAccentColor,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(
                                text = when {
                                    hasRoot -> "已授权"
                                    isRequesting -> "请求中"
                                    else -> "获取权限"
                                },
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                // Rustic 配置
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = cardBackground
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = "Rustic",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        // 目录 + 密码 + 密码提示：和设置页共用同一份实现
                        RepoSetupForm(
                            path = rusticPath,
                            onPickFolder = { folderPickerLauncher.launch(null) },
                            password = rusticPassword,
                            onPasswordChange = onRusticPasswordChange,
                            enabled = rusticEnabled
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // 初始化 rustic 仓库按钮
                        Button(
                            onClick = onInitRusticRepo,
                            enabled = canInitRepo,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = accentColor,
                                contentColor = onAccentColor
                            ),
                            contentPadding = PaddingValues(vertical = 12.dp)
                        ) {
                            if (isInitializing) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .width(18.dp)
                                        .height(18.dp),
                                    color = onAccentColor,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "初始化中…",
                                    fontWeight = FontWeight.Medium
                                )
                            } else if (isRepoReady) {
                                Text(
                                    text = "已初始化",
                                    fontWeight = FontWeight.Medium
                                )
                            } else {
                                Text(
                                    text = "初始化 rustic 仓库",
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                // 完成引导：需要 Root 权限 + rustic 仓库已就绪
                if (!hasRoot || !isRepoReady) {
                    Text(
                        text = if (!hasRoot) "请先获取 Root 权限" else "请先初始化 rustic 仓库",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }

                Button(
                    onClick = onEnterApp,
                    enabled = hasRoot && isRepoReady,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp)
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accentColor,
                        contentColor = onAccentColor
                    )
                ) {
                    Text(
                        text = "完成引导",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }

    // 初始化失败：把 rustic 的原始错误完整展示出来
    initError?.let { message ->
        AlertDialog(
            onDismissRequest = onDismissError,
            title = { Text("初始化失败") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onDismissError) {
                    Text("知道了")
                }
            }
        )
    }
}
