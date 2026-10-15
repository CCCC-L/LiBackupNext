package com.cc.libackupnext

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
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
import com.cc.libackupnext.service.AppManagerService
import com.cc.libackupnext.service.RootManager
import com.cc.libackupnext.service.RusticService
import com.cc.libackupnext.ui.WelcomeScreen
import com.cc.libackupnext.ui.screen.AppListScreen
import com.cc.libackupnext.ui.theme.AppManagerTheme
import com.cc.libackupnext.util.RepoPath
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


        setContent {
            AppManagerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppRoot()
                }
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val context = LocalContext.current

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

    var hasRoot by remember { mutableStateOf(false) }
    var isChecking by remember { mutableStateOf(true) }
    var isRequesting by remember { mutableStateOf(false) }
    var isInitializing by remember { mutableStateOf(false) }
    var hasEnteredApp by remember { mutableStateOf(false) }
    var initError by remember { mutableStateOf<String?>(null) }

    // rustic 仓库是否已就绪（初始化过 / 校验过密码）
    var isRepoReady by remember { mutableStateOf(AppPrefs.hasRepository()) }

    var rusticPath by rememberSaveable { mutableStateOf(AppPrefs.repoPath) }
    var rusticPassword by rememberSaveable {
        mutableStateOf(AppPrefs.repoPassword.ifBlank { "rustic" })
    }

    val acquireRoot: suspend () -> Boolean = {
        val granted = withContext(Dispatchers.IO) {
            RootManager.isGranted() || RootManager.request()
        }
        android.util.Log.d("AppRoot", "acquireRoot granted=$granted")
        if (granted) {
            hasRoot = true
            // 提前把两个 root 服务绑好，后面调用不用再等 su 握手
            scope.launch(Dispatchers.IO) {
                // 升级后旧进程还在跑上一版代码，先断开、等它真正退出再绑定
                AppManagerService.restart()
                RusticService.restart()
                delay(800.milliseconds)
                AppManagerService.warmUp()
                RusticService.warmUp()
                // 上次备份如果被强杀，目标应用可能还停在 SIGSTOP 状态，这里恢复
                runCatching { AppManagerService.resumeAllPaused() }
            }
        }
        granted
    }

    LaunchedEffect(Unit) {
        acquireRoot()
        isChecking = false
    }

    // 已有 Root 且仓库已就绪 → 直接进主页面，否则停在引导页
    LaunchedEffect(hasRoot, isChecking) {
        if (isChecking || !hasRoot) return@LaunchedEffect
        if (!isRepoReady || !AppPrefs.hasRepository()) return@LaunchedEffect

        // 仓库目录可能被删掉、或所在存储没挂载：跳过欢迎页前先确认它还在
        val path = AppPrefs.repoPath
        val exists = withContext(Dispatchers.IO) {
            path.isNotBlank() && File(path).isDirectory
        }

        if (exists) {
            hasEnteredApp = true
        } else {
            // 目录不在了就停在欢迎页，要求重新选择 / 初始化
            isRepoReady = false
            Toast.makeText(
                context,
                "仓库目录不存在：$path",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    when {
        isChecking -> Box(modifier = Modifier.fillMaxSize())

        hasEnteredApp -> AppListScreen()

        else -> WelcomeScreen(
            hasRoot = hasRoot,
            isRequesting = isRequesting,
            isInitializing = isInitializing,
            isRepoReady = isRepoReady,
            rusticPath = rusticPath,
            onRusticPathChange = { raw ->
                val path = RepoPath.toFilesystemPath(raw)
                if (path == null) {
                    Toast.makeText(context, "无法解析该目录", Toast.LENGTH_SHORT).show()
                } else {
                    rusticPath = path
                    // 换了目录就必须重新初始化
                    isRepoReady = false
                    AppPrefs.clearRepository()
                }
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
            onInitRusticRepo = {
                if (!isInitializing && rusticPath.isNotBlank()) {
                    isInitializing = true
                    initError = null
                    scope.launch {
                        val result = RusticService.initRepository(rusticPath, rusticPassword)
                        isInitializing = false

                        result
                            .onSuccess {
                                isRepoReady = true
                                Toast.makeText(context, "仓库初始化成功", Toast.LENGTH_SHORT).show()
                            }
                            .onFailure {
                                initError = it.message ?: "未知错误"
                            }
                    }
                }
            },
            onEnterApp = {
                if (hasRoot && isRepoReady) hasEnteredApp = true
            },
            initError = initError,
            onDismissError = { initError = null }
        )
    }
}
