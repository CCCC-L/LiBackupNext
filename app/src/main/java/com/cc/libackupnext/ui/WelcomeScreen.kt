package com.cc.libackupnext.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cc.libackupnext.data.FolderPickerLauncher
import com.cc.libackupnext.data.FolderPickerMode
import com.cc.libackupnext.data.RemoteRepo
import com.cc.libackupnext.service.RcloneClient
import com.cc.libackupnext.service.RusticClient
import com.cc.libackupnext.ui.theme.SearchBarDurationMs
import com.cc.libackupnext.ui.theme.dialogContainer
import com.cc.libackupnext.ui.theme.rootCardGrantedBackground
import com.cc.libackupnext.ui.theme.rootCardGrantedTextColor
import com.cc.libackupnext.ui.theme.rootCardGrantedTextVariant
import com.cc.libackupnext.ui.theme.rootGrantedButtonBackground
import com.cc.libackupnext.ui.theme.rootGrantedButtonContent
import com.cc.libackupnext.ui.util.RepoSetupForm
import com.cc.libackupnext.util.AppLog
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
    /** 位置 / 仓库密码 / 远程配置（本地页传 null）；后端选项由业务层派生。 */
    onInitRusticRepo: (String, String, RemoteRepo?) -> Unit,
    /** 打开通用全屏文件夹选择弹窗；选中的路径回填到当前表单。 */
    onOpenFolderPicker: (FolderPickerLauncher) -> Unit,
    onEnterApp: () -> Unit,
    initError: String?,
    onDismissError: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp

    // 本地 / 远程共用一份配置，协议在服务协议下拉里切换，不再左右滑动分页
    var repo by remember {
        mutableStateOf(RemoteRepo(path = rusticPath))
    }
    var testing by remember { mutableStateOf(false) }
    // 测试连接失败的原因：内联显示在表单下方，不再用 Toast
    var testError by remember { mutableStateOf<String?>(null) }
    var confirmPassword by remember { mutableStateOf("") }
    val isLocal = repo.protocol.isLocal

    // MainActivity 仍持有本地目录状态，两边保持同步
    LaunchedEffect(rusticPath) {
        if (repo.protocol.isLocal && repo.path != rusticPath) {
            repo = repo.copy(path = rusticPath)
        }
    }

    val pageBackground = colors.surfaceContainer
    // 初始化仓库卡片保持亮一档的 surface（#F8F9FF），与页面底 #EAEEF7 分层。
    val cardBackground = colors.surface
    val rootCardBackground =
        if (hasRoot) colors.rootCardGrantedBackground else cardBackground
    val onRootCardColor =
        if (hasRoot) colors.rootCardGrantedTextColor else colors.onSurface
    val onRootCardVariant =
        if (hasRoot) colors.rootCardGrantedTextVariant else colors.onSurfaceVariant
    val accentColor = colors.primary
    val onAccentColor = colors.onPrimary

    // 远程同样跑在 :root 进程里，Root 仍是硬前提
    val rusticEnabled = hasRoot

    /** 远程路径浏览的前置门禁 / 手动测试连接：成功 Toast、失败原因内联显示。 */
    fun runWhenConnected(action: () -> Unit = {}) {
        if (testing || !repo.isComplete()) return
        scope.launch {
            testing = true
            testError = null
            val result = RcloneClient.testRemoteConnection(repo)
            result
                .onSuccess { AppLog.toastOnMain("连接成功") }
                .onFailure { testError = it.message ?: "未知错误" }
            testing = false
            if (result.isSuccess) action()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = pageBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
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

                // 只有本地协议显示 Root 卡片。切换到远程时高度收缩 + 上移淡出，
                // 下面的 Rustic 卡片顺势顶上来，看起来像 Root 卡片被挤走而不是硬切消失。
                AnimatedVisibility(
                    visible = isLocal,
                    enter = expandVertically(
                        animationSpec = tween(SearchBarDurationMs),
                        expandFrom = Alignment.Top
                    ) + fadeIn(animationSpec = tween(SearchBarDurationMs)),
                    exit = shrinkVertically(
                        animationSpec = tween(SearchBarDurationMs),
                        shrinkTowards = Alignment.Top
                    ) + slideOutVertically(animationSpec = tween(SearchBarDurationMs)) { -it / 3 } +
                            fadeOut(animationSpec = tween(200))
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        shape = MaterialTheme.shapes.large,
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
                                shape = MaterialTheme.shapes.extraLarge,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = accentColor,
                                    contentColor = onAccentColor,
                                    disabledContainerColor = colors.rootGrantedButtonBackground,
                                    disabledContentColor = colors.rootGrantedButtonContent
                                ),
                                contentPadding = PaddingValues(
                                    horizontal = 20.dp,
                                    vertical = 12.dp
                                )
                            ) {
                                if (isRequesting) {
                                    LoadingIndicator(
                                        modifier = Modifier
                                            .width(18.dp)
                                            .height(18.dp),
                                        color = onAccentColor
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
                }
                // 非本地协议：整块 Root 卡片连同任何 Root 字样都不显示。
                // acquireRoot() 仍照常在 MainActivity 里跑，hasRoot 依旧是进入主页面的门槛。

                // Rustic 配置：本地和远程共用一个窗口，字段随服务协议变化
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    shape = MaterialTheme.shapes.large,
                    color = cardBackground
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = if (isLocal) "Rustic 本地仓库" else "Rustic 远程仓库",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        RepoSetupForm(
                            repo = repo,
                            onRepoChange = { next ->
                                // 切回本地时把已选目录带回来，不让用户重选一次
                                repo = if (next.protocol.isLocal && next.path.isBlank()) {
                                    next.copy(path = rusticPath)
                                } else next
                                testError = null
                            },
                            password = rusticPassword,
                            onPasswordChange = {
                                onRusticPasswordChange(it)
                                testError = null
                            },
                            confirmPassword = confirmPassword,
                            onConfirmPasswordChange = {
                                confirmPassword = it
                                testError = null
                            },
                            enabled = rusticEnabled && !isInitializing,
                            onPickFolder = {
                                onOpenFolderPicker(
                                    FolderPickerLauncher(
                                        mode = FolderPickerMode.LOCAL,
                                        initialPath = repo.path,
                                        onPick = { picked ->
                                            repo = repo.copy(path = picked)
                                            onRusticPathChange(picked)
                                        }
                                    )
                                )
                            },
                            onBrowseRemotePath = {
                                onOpenFolderPicker(
                                    FolderPickerLauncher(
                                        mode = FolderPickerMode.REMOTE,
                                        repo = repo,
                                        initialPath = repo.path,
                                        onPick = { picked -> repo = repo.copy(path = picked) }
                                    )
                                )
                            },
                            onConnectedAction = { action -> runWhenConnected(action) },
                            isTesting = testing,
                            onInit = {
                                testError = null
                                if (isLocal) {
                                    onInitRusticRepo(repo.path, rusticPassword, null)
                                } else {
                                    onInitRusticRepo(repo.location, rusticPassword, repo)
                                }
                            }
                        )

                        // 测试连接失败：和初始化失败一样留在表单里，错误原文已由 service 层精简
                        testError?.let { message ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.error
                            )
                        }

                        if (isInitializing) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                LoadingIndicator(
                                    modifier = Modifier.width(18.dp).height(18.dp),
                                    color = accentColor
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("初始化中…", style = MaterialTheme.typography.bodyMedium)
                            }
                        } else if (isRepoReady) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text("已初始化", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                // 完成引导：需要 Root 权限 + rustic 仓库已就绪
                // 非本地时底部也不出现「请先获取 Root 权限」这类字样
                if (!hasRoot || !isRepoReady) {
                    Text(
                        text = when {
                            !hasRoot && isLocal -> "请先获取 Root 权限"
                            !isLocal -> "请先初始化 rustic 仓库"
                            else -> "请先初始化 rustic 仓库"
                        },
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
                    shape = MaterialTheme.shapes.large,
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
            containerColor = MaterialTheme.colorScheme.dialogContainer,
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
