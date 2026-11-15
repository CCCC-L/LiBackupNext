package com.cc.libackupnext.ui.screen

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.cc.libackupnext.BuildConfig
import com.cc.libackupnext.data.ReleaseInfo
import com.cc.libackupnext.service.UpdateChecker
import com.cc.libackupnext.ui.AppTopBar
import com.cc.libackupnext.ui.PageContentHorizontalPadding
import com.cc.libackupnext.ui.dialog.UpdateDialog
import com.cc.libackupnext.util.AppLinks
import com.cc.libackupnext.util.AppLog
import kotlinx.coroutines.launch

/**
 * 关于页：设置 → 关于。
 *
 * 和「仓库」页一样是压在底栏之上的独立页面，顶栏直接复用主 tab 页的 [AppTopBar]。
 * 头部（图标 / 鸣谢 / 胶囊）是独立版式；下面的版本卡与两个小节用 [CardGroup] +
 * [SettingsCard]，和设置页、apptab 的列表卡片是同一套形状。
 * 开源软件声明走独立页面（[onOpenOpenSource]），这里不再弹窗。
 * 只展示版本信息、跳转项目外链，不读写仓库、不碰凭据。
 */
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onOpenOpenSource: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pageBackground = MaterialTheme.colorScheme.surfaceContainer
    val searchBarBackground = MaterialTheme.colorScheme.surfaceContainerHighest

    var checking by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<ReleaseInfo?>(null) }

    val versionCode = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        }.getOrDefault(-1L)
    }
    val buildType = if (BuildConfig.DEBUG) "debug" else "release"
    // 启动图标是 adaptive-icon XML，Compose 的 painterResource 画不出来；
    // 直接拿系统解析好的 Drawable 转成位图，深浅色 / 各厂商都能正常显示。
    val iconBitmap: ImageBitmap? = remember(context) {
        runCatching {
            context.packageManager.getApplicationIcon(context.packageName)
                .toBitmap(width = 192, height = 192)
                .asImageBitmap()
        }.getOrNull()
    }

    fun openUrl(url: String) {
        val ok = runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
        if (!ok) AppLog.toastOnMain("没有可用的浏览器")
    }

    /** 手动检查更新：和启动时那次相互独立，所以这里要有明确的成功 / 失败反馈。 */
    fun checkUpdate() {
        if (checking) return
        checking = true
        scope.launch {
            val result = UpdateChecker.check()
            checking = false
            result
                .onSuccess { info ->
                    if (info == null) AppLog.toastOnMain("已是最新版本")
                    else updateInfo = info
                }
                .onFailure {
                    AppLog.toastOnMain("检查失败：${it.message ?: "未知错误"}")
                }
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = pageBackground,
        topBar = {
            AppTopBar(
                viewModel = null,
                title = "关于",
                pageBackground = pageBackground,
                searchBarBackground = searchBarBackground,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                showSearch = false,
                showSortMenu = false,
                showMiddleButton = false,
                showUserButton = false,
                // 空动作区：跳过默认的「用户 / 排序 / 更多」按钮分支，避免带出空菜单
                customActions = {}
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PageContentHorizontalPadding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.size(132.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (iconBitmap != null) {
                        Image(
                            bitmap = iconBitmap,
                            contentDescription = "应用图标",
                            modifier = Modifier.size(88.dp)
                        )
                    } else {
                        Icon(
                            Icons.Filled.Android,
                            contentDescription = "应用图标",
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = "锂备份",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "特别鸣谢：rustic · rclone · 数据备份 · 备份工具箱 · Metric",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.tertiary
            )

            Spacer(modifier = Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AboutChip(
                    text = "意见反馈",
                    icon = Icons.Filled.Email,
                    onClick = { openUrl(AppLinks.ISSUES_URL) },
                    modifier = Modifier.weight(1f)
                )
                AboutChip(
                    text = "项目主页",
                    icon = Icons.Filled.Code,
                    onClick = { openUrl(AppLinks.PROJECT_URL) },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(26.dp))
            // 版本卡片也用设置页的分组卡片，右侧按钮是唯一保留的头部操作
            CardGroup(
                null,
                { shape ->
                    SettingsCard(
                        shape = shape,
                        title = "当前版本",
                        subtitle = "${BuildConfig.APP_VERSION}-$buildType ($versionCode)",
                        trailing = {
                            Surface(
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(MaterialTheme.shapes.medium)
                                    .clickable(enabled = !checking) { checkUpdate() }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    if (checking) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.onPrimary
                                        )
                                    } else {
                                        Icon(
                                            Icons.Filled.Download,
                                            contentDescription = "检查更新",
                                            tint = MaterialTheme.colorScheme.onPrimary
                                        )
                                    }
                                }
                            }
                        }
                    )
                }
            )

            CardGroup(
                "开发者",
                { shape ->
                    SettingsCard(
                        shape = shape,
                        title = AppLinks.OWNER,
                        subtitle = "开发者",
                        trailing = {
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                LinkIcon(Icons.Filled.Code, "项目主页") {
                                    openUrl(AppLinks.PROJECT_URL)
                                }
                                LinkIcon(Icons.Filled.Person, "GitHub 主页") {
                                    openUrl(AppLinks.OWNER_URL)
                                }
                            }
                        }
                    )
                }
            )

            CardGroup(
                "其他",
                { shape ->
                    SettingsCard(
                        shape = shape,
                        title = "开源软件声明",
                        subtitle = "查看软件使用的开源组件",
                        onClick = onOpenOpenSource
                    )
                }
            )

            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "基于 rustic 构建 · 需要 root 权限",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = AppLinks.PROJECT_URL,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { openUrl(AppLinks.PROJECT_URL) }
            )
            Spacer(modifier = Modifier.height(28.dp))
        }
    }

    updateInfo?.let { info ->
        UpdateDialog(info = info, onDismiss = { updateInfo = null })
    }
}

@Composable
private fun AboutChip(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .height(46.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(19.dp),
            tint = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
    }
}

@Composable
private fun RowScope.LinkIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    Icon(
        imageVector = icon,
        contentDescription = description,
        modifier = Modifier
            .size(22.dp)
            .clickable(onClick = onClick),
        tint = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
