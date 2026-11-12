package com.cc.libackupnext

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.cc.libackupnext.data.AppPrefs
import com.cc.libackupnext.data.FolderPickerLauncher
import com.cc.libackupnext.service.AppManagerService
import com.cc.libackupnext.service.RootManager
import com.cc.libackupnext.data.ReleaseInfo
import com.cc.libackupnext.service.RusticClient
import com.cc.libackupnext.service.UpdateChecker
import com.cc.libackupnext.ui.HomeTab
import com.cc.libackupnext.ui.WelcomeScreen
import com.cc.libackupnext.ui.dialog.UpdateDialog
import com.cc.libackupnext.ui.screen.AppListScreen
import com.cc.libackupnext.ui.dialog.FolderPickerDialog
import com.cc.libackupnext.ui.theme.AppManagerTheme
import com.cc.libackupnext.util.AppLog
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // 只在 debug 包里打开 su 命令日志，release 不刷屏也不泄漏命令内容
        Shell.enableVerboseLogging = BuildConfig.DEBUG
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)


        // 崩溃续命后重建的 Activity 带这个标记：跳过引导页，直接落到设置 tab
        val crashRecovery = intent?.getBooleanExtra(AppLog.EXTRA_CRASH_RECOVERY, false) == true

        setContent {
            AppManagerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppRoot(crashRecovery = crashRecovery)
                }
            }
        }
    }
}

@Composable
private fun AppRoot(crashRecovery: Boolean) {
    val context = LocalContext.current

    // 启动时检查更新：单独一个 effect，不阻塞 root 授权流程。
    // 开关关掉 / 无网络 / GitHub 限流一律静默，只有确实查到新版本才弹窗。
    var updateInfo by remember { mutableStateOf<ReleaseInfo?>(null) }
    LaunchedEffect(Unit) {
        if (AppPrefs.checkUpdateOnLaunch) {
            UpdateChecker.check().getOrNull()?.let { updateInfo = it }
        }
    }

    // 备份 / 恢复的进度通知需要通知权限（Android 13+）
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val scope = rememberCoroutineScope()

    // 上次运行异常退出（含 native abort / 系统杀进程）：下次启动稳定补一次提示
    LaunchedEffect(Unit) {
        if (AppLog.hasPendingCrash()) {
            AppLog.toastOnMain("上次运行出现异常，日志已保存，可在 设置 → 导出日志 查看", longToast = true)
            AppLog.consumePendingCrash()
        }
    }

    // 崩溃续命后的收尾：恢复被 SIGSTOP 的应用，别让它们一直卡在暂停状态
    DisposableEffect(Unit) {
        AppLog.recoveryHook = {
            scope.launch(Dispatchers.IO) {
                runCatching { AppManagerService.resumeAllPaused() }
                runCatching { AppPrefs.clearPausedApps() }
            }
        }
        onDispose { AppLog.recoveryHook = null }
    }

    var hasRoot by remember { mutableStateOf(false) }
    var isChecking by remember { mutableStateOf(true) }
    var isRequesting by remember { mutableStateOf(false) }
    var isInitializing by remember { mutableStateOf(false) }
    // 崩溃恢复时直接进主页面，保证设置页（导出日志）立刻可达
    var hasEnteredApp by rememberSaveable { mutableStateOf(crashRecovery) }
    var initError by remember { mutableStateOf<String?>(null) }

    // 文件夹选择弹窗由 Activity 顶层持有：底层表单状态不会因为开关弹窗被 dispose 掉
    var folderPickerLauncher by remember { mutableStateOf<FolderPickerLauncher?>(null) }
    val openFolderPicker: (FolderPickerLauncher) -> Unit = { folderPickerLauncher = it }

    // rustic 仓库是否已就绪（初始化过 / 校验过密码）
    var isRepoReady by remember { mutableStateOf(AppPrefs.hasRepository()) }

    // 远程仓库没有本地目录，切回本地页时别把逻辑标识当成路径显示
    var rusticPath by rememberSaveable {
        mutableStateOf(if (AppPrefs.isRemote) "" else AppPrefs.repoPath)
    }
    var rusticPassword by rememberSaveable {
        mutableStateOf(AppPrefs.repoPassword)
    }

    val acquireRoot: suspend () -> Boolean = {
        val granted = withContext(Dispatchers.IO) {
            RootManager.isGranted() || RootManager.request()
        }
        AppLog.breadcrumb("启动", "root 授权结果=$granted")
        if (granted) {
            hasRoot = true
            // 提前绑定 root 服务，后面调用不用再等 su 握手
            scope.launch(Dispatchers.IO) {
                // 升级后旧进程还在跑上一版代码，先断开、等它真正退出再绑定
                AppManagerService.restart()
                RusticClient.restart()
                delay(800.milliseconds)
                AppManagerService.warmUp()
                // 上次备份如果被强杀，目标应用可能还停在 SIGSTOP 状态，这里恢复
                runCatching { AppManagerService.resumeAllPaused() }
            }
        }
        granted
    }

    // 老版本升级：清单为空但已有当前仓库时补一条，避免「已添加仓库」页一片空白
    LaunchedEffect(Unit) {
        AppPrefs.ensureCurrentRepoRecorded()
    }

    LaunchedEffect(Unit) {
        acquireRoot()
        isChecking = false
    }

    // 已有 Root 且仓库已就绪 → 直接进主页面，否则停在引导页
    LaunchedEffect(hasRoot, isChecking) {
        if (isChecking || !hasRoot) return@LaunchedEffect
        if (!isRepoReady || !AppPrefs.hasRepository()) return@LaunchedEffect

        // 远程仓库没有本地目录可查，交给后续连接验证；本地才做目录自检
        if (AppPrefs.isRemote) {
            AppLog.breadcrumb("启动", "远程仓库，跳过欢迎页，进入主页面")
            hasEnteredApp = true
            return@LaunchedEffect
        }

        // 仓库目录可能被删掉、或所在存储没挂载：跳过欢迎页前先确认它还在
        val path = AppPrefs.repoPath
        val exists = withContext(Dispatchers.IO) {
            path.isNotBlank() && File(path).isDirectory
        }

        if (exists) {
            AppLog.breadcrumb("启动", "跳过欢迎页，进入主页面")
            hasEnteredApp = true
        } else {
            // 目录不在了就停在欢迎页，要求重新选择 / 初始化
            AppLog.breadcrumb("启动", "仓库目录不存在，停在欢迎页：$path")
            isRepoReady = false
            AppLog.toastOnMain("仓库目录不存在：$path", longToast = true)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            isChecking -> Box(modifier = Modifier.fillMaxSize())

            hasEnteredApp -> AppListScreen(
                initialTab = if (crashRecovery) HomeTab.SETTINGS else HomeTab.APPS,
                onOpenFolderPicker = openFolderPicker
            )

            else -> WelcomeScreen(
                hasRoot = hasRoot,
                isRequesting = isRequesting,
                isInitializing = isInitializing,
                isRepoReady = isRepoReady,
                rusticPath = rusticPath,
                // 弹窗直接回填绝对路径，不再走 SAF 的 tree Uri 转换
                onRusticPathChange = { path ->
                    rusticPath = path
                    // 换目录后必须重新初始化并验证密码。只有初始化成功才会覆盖
                    // AppPrefs 里保存的仓库地址和密码，失败时保留原来的配置
                    isRepoReady = false
                },
                rusticPassword = rusticPassword,
                onRusticPasswordChange = { rusticPassword = it },
                onRequestRoot = {
                    if (!isRequesting && !hasRoot) {
                        isRequesting = true
                        scope.launch {
                            acquireRoot()
                            isRequesting = false
                        }
                    }
                },
                onInitRusticRepo = { path, password, remote ->
                    if (!isInitializing && path.isNotBlank()) {
                        isInitializing = true
                        initError = null
                        scope.launch {
                            // 远程的位置与后端选项都由业务层派生；本地沿用用户选的目录
                            val result = if (remote != null) {
                                RusticClient.initRemoteRepository(remote, password)
                            } else {
                                RusticClient.initRepository(path, password)
                            }
                            isInitializing = false

                            result
                                .onSuccess {
                                    // 远程配置和「已添加仓库」清单都由业务层在成功后统一写入
                                    if (remote == null) rusticPath = path
                                    isRepoReady = true
                                    AppLog.toastOnMain("仓库初始化成功")
                                }
                                .onFailure {
                                    val message = it.message ?: "未知错误"
                                    initError = message
                                    AppLog.toastOnMain(message, longToast = true)
                                }
                        }
                    }
                },
                onOpenFolderPicker = openFolderPicker,
                onEnterApp = {
                    if (hasRoot && isRepoReady) hasEnteredApp = true
                },
                initError = initError,
                onDismissError = { initError = null }
            )
        }

    }

    // 文件夹选择弹窗是独立窗口，挂在 Activity 顶层；底层页面保持挂载，表单数据不丢。
    // 不管选中还是返回都要 onClose，让底层「配置远程仓库 / 选择并初始化仓库」弹窗
    // 重新显示并拿到回填值。
    folderPickerLauncher?.let { launcher ->
        FolderPickerDialog(
            mode = launcher.mode,
            repo = launcher.repo,
            initialPath = launcher.initialPath,
            allowRoot = launcher.allowRoot,
            onPick = { path ->
                launcher.onPick(path)
                folderPickerLauncher = null
                launcher.onClose()
            },
            onBack = {
                folderPickerLauncher = null
                launcher.onClose()
            }
        )
    }

    // 更新弹窗挂在最外层：欢迎页 / 主页面都能弹，关掉就不再出现（下次启动再查）
    updateInfo?.let { info ->
        UpdateDialog(info = info, onDismiss = { updateInfo = null })
    }
}
