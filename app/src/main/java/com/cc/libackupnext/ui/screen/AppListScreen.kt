package com.cc.libackupnext.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cc.libackupnext.entity.AppInfo
import com.cc.libackupnext.ui.AppTopBar
import com.cc.libackupnext.ui.MainViewModel
import com.cc.libackupnext.ui.SortType
import com.cc.libackupnext.ui.UserBadge
import com.cc.libackupnext.ui.isNonPrimaryUser
import com.cc.libackupnext.ui.util.AppItemCard
import com.cc.libackupnext.ui.util.AppListContainer
import com.cc.libackupnext.ui.util.formatShortDateTime
import com.cc.libackupnext.ui.util.rememberAppIconWithBackup

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppListScreen(viewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val appName = remember {
        context.applicationInfo.loadLabel(context.packageManager).toString()
    }

    val displayApps by viewModel.displayApps.collectAsState()
    val backupSummaries by viewModel.backupSummaries.collectAsState()
    val sortType by viewModel.sortType.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val loadError by viewModel.loadError.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val scrollTrigger by viewModel.scrollToTopTrigger.collectAsState()

    var selectedApp by remember { mutableStateOf<AppInfo?>(null) }
    // 记住打开的是哪个包，进程被回收后还能回到这个页面
    var selectedPackage by rememberSaveable { mutableStateOf<String?>(null) }
    // 新建应用备份页
    var showNewBackup by rememberSaveable { mutableStateOf(false) }
    // 管理应用备份页
    var showManageBackup by rememberSaveable { mutableStateOf(false) }
    // 设置页
    var showSettings by rememberSaveable { mutableStateOf(false) }
    // 错误日志页
    var showErrorLog by rememberSaveable { mutableStateOf(false) }
    // 详情页是从哪个批量页进来的：返回时回到原页面
    var detailFrom by remember { mutableStateOf<AppRoute?>(null) }
    // 批量页列表的滚动位置：页面被重建时还能回到原来的位置
    val batchListState = rememberLazyListState()

    val allApps by viewModel.allApps.collectAsState()
    val currentUserId by viewModel.currentUserId.collectAsState()

    // 切换用户 / 进程恢复时，保持当前打开的页面，只把 userId 换成新用户
    LaunchedEffect(currentUserId, allApps, selectedPackage) {
        val pkg = selectedPackage ?: return@LaunchedEffect
        if (selectedApp?.packageName == pkg && selectedApp?.userId == currentUserId) {
            return@LaunchedEffect
        }

        val found = allApps.firstOrNull { it.packageName == pkg && it.userId == currentUserId }
        selectedApp = found ?: selectedApp?.takeIf { it.packageName == pkg }?.copy(userId = currentUserId)
    }

    // 进程被回收 / root 服务被杀后回到前台，列表可能是空的，这里自动补一次
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (viewModel.allApps.value.isEmpty() || viewModel.loadError.value != null) {
                    viewModel.loadApps(scrollToTop = false)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val isDark = isSystemInDarkTheme()
    val pageBackground =
        if (isDark) MaterialTheme.colorScheme.surfaceContainerLow else Color(0xFFEAEEF7)
    val searchBarBackground =
        if (isDark) MaterialTheme.colorScheme.surfaceContainerHighest else Color(0xFFE2E7F1)

    // LazyColumn 状态，用于滚动控制
    val listState = rememberLazyListState()

    // 下拉刷新状态（指示器要用）
    val pullState = rememberPullToRefreshState()

    // 滚动触发器变化 → 回到顶部
    LaunchedEffect(scrollTrigger) {
        if (scrollTrigger > 0) {
            listState.scrollToItem(0)
        }
    }

    BackHandler(enabled = isSearching) {
        viewModel.toggleSearch()
    }

    // 新建备份页优先响应返回
    BackHandler(enabled = showNewBackup) {
        showNewBackup = false
    }

    // 管理备份页优先响应返回
    BackHandler(enabled = showManageBackup) {
        showManageBackup = false
    }

    // 设置页优先响应返回
    BackHandler(enabled = showSettings) {
        showSettings = false
    }

    // 错误日志页优先响应返回
    BackHandler(enabled = showErrorLog) {
        showErrorLog = false
    }

    // 页面内容单独记一份：切用户时 AppInfo 变了但页面不变，避免重放进入动画
    var pageApp by remember { mutableStateOf<AppInfo?>(null) }
    LaunchedEffect(selectedApp) {
        if (selectedApp != null) pageApp = selectedApp
    }

    val route = when {
        showNewBackup -> AppRoute.NEW_BACKUP
        showManageBackup -> AppRoute.MANAGE_BACKUP
        showSettings -> AppRoute.SETTINGS
        showErrorLog -> AppRoute.ERROR_LOG
        selectedApp != null -> AppRoute.DETAIL
        else -> AppRoute.LIST
    }

    AnimatedContent(
        targetState = route,
        transitionSpec = {
            // 层级越深越靠右：进入时从右滑入，返回时反向
            if (targetState.ordinal > initialState.ordinal) {
                (slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = tween(300)
                ) + fadeIn(animationSpec = tween(300))) togetherWith
                        (slideOutHorizontally(
                            targetOffsetX = { -it / 3 },
                            animationSpec = tween(300)
                        ) + fadeOut(animationSpec = tween(300)))
            } else {
                (slideInHorizontally(
                    initialOffsetX = { -it / 3 },
                    animationSpec = tween(300)
                ) + fadeIn(animationSpec = tween(300))) togetherWith
                        (slideOutHorizontally(
                            targetOffsetX = { it },
                            animationSpec = tween(300)
                        ) + fadeOut(animationSpec = tween(300)))
            }.using(SizeTransform(clip = false))
        },
        label = "PageTransition"
    ) { target ->
        when (target) {
            AppRoute.NEW_BACKUP -> AppBackupScreen(
                viewModel = viewModel,
                onBack = { showNewBackup = false },
                onSwitchMode = {
                    if (isSearching) viewModel.toggleSearch()
                    showNewBackup = false
                    showManageBackup = true
                },
                onOpenAppDetail = { app ->
                    detailFrom = AppRoute.NEW_BACKUP
                    showNewBackup = false
                    selectedApp = app
                    selectedPackage = app.packageName
                },
                listState = batchListState
            )

            AppRoute.MANAGE_BACKUP -> ManageBackupScreen(
                viewModel = viewModel,
                onBack = { showManageBackup = false },
                onSwitchMode = {
                    if (isSearching) viewModel.toggleSearch()
                    showManageBackup = false
                    showNewBackup = true
                },
                onOpenAppDetail = { app ->
                    detailFrom = AppRoute.MANAGE_BACKUP
                    showManageBackup = false
                    selectedApp = app
                    selectedPackage = app.packageName
                },
                listState = batchListState
            )

            AppRoute.DETAIL -> pageApp?.let { current ->
                AppBackupScreen(
                    viewModel = viewModel,
                    app = current,
                    onBack = {
                        selectedApp = null
                        selectedPackage = null
                        when (detailFrom) {
                            AppRoute.NEW_BACKUP -> showNewBackup = true
                            AppRoute.MANAGE_BACKUP -> showManageBackup = true
                            else -> Unit
                        }
                        detailFrom = null
                    },
                    // 只有从管理备份页进来才「点备份 = 选中并返回」，
                    // 从应用列表进来点卡片还是改备注
                    onSelectBackup = if (detailFrom == AppRoute.MANAGE_BACKUP) {
                        { entry ->
                            viewModel.pendingSingleSelection =
                                current.packageName to entry.availableKinds.toSet()
                            viewModel.selectedEntries[current.packageName] = entry
                            selectedApp = null
                            selectedPackage = null
                            detailFrom = null
                            showManageBackup = true
                        }
                    } else {
                        null
                    }
                )
            }

            AppRoute.SETTINGS -> SettingsScreen(
                onBack = { showSettings = false },
                onSettingChanged = { viewModel.loadApps(scrollToTop = false) }
            )

            AppRoute.ERROR_LOG -> ErrorLogScreen(
                onBack = { showErrorLog = false }
            )

            AppRoute.LIST -> Scaffold(
                containerColor = pageBackground,
                topBar = {
                    AppTopBar(
                        viewModel = viewModel,
                        title = appName,
                        pageBackground = pageBackground,
                        searchBarBackground = searchBarBackground,
                        onCreateBackup = {
                            if (isSearching) viewModel.toggleSearch()
                            showNewBackup = true
                        },
                        onManageBackup = {
                            if (isSearching) viewModel.toggleSearch()
                            showManageBackup = true
                        },
                        onOpenSettings = {
                            if (isSearching) viewModel.toggleSearch()
                            showSettings = true
                        },
                        onOpenErrorLog = {
                            if (isSearching) viewModel.toggleSearch()
                            showErrorLog = true
                        }
                    )
                }
            ) { paddingValues ->
                val contentModifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)

                // 下拉刷新：手势只作用于列表，刷新时不显示全屏 loading
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = { viewModel.refresh() },
                    modifier = contentModifier,
                    state = pullState,
                    indicator = {
                        PullToRefreshDefaults.LoadingIndicator(
                            state = pullState,
                            isRefreshing = isRefreshing,
                            modifier = Modifier.align(Alignment.TopCenter)
                        )
                    }
                ) {
                    when {
                        displayApps.isEmpty() && isLoading ->
                            LoadingState(Modifier.fillMaxSize())

                        displayApps.isEmpty() && loadError != null -> ErrorState(
                            modifier = Modifier.fillMaxSize(),
                            message = loadError.orEmpty(),
                            onRetry = { viewModel.loadApps() }
                        )

                        else -> AppListContainer(
                            items = displayApps,
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            key = { _, app -> app.packageName }
                        ) { _, app, shape ->
                            val userBadge: (@Composable () -> Unit)? =
                                if (isNonPrimaryUser(app.userId)) {
                                    { UserBadge(userId = app.userId) }
                                } else {
                                    null
                                }

                            AppItemCard(
                                // 本机取不到图标（其他用户的应用 / 只在备份里）就回落到仓库图标
                                icon = rememberAppIconWithBackup(app.packageName, app.userId),
                                iconPlaceholder = app.name,
                                frozen = app.isFrozen,
                                dimmed = app.onlyInBackup,
                                title = app.name,
                                subtitle = app.packageName,
                                // 备份数放在包名下面（版本号不再显示）
                                bottomText = "${backupSummaries[app.packageName]?.count ?: 0}个备份",
                                // 按备份时间排序时，再下面显示备份时间；没备份就不显示
                                extraBottomText = if (sortType == SortType.BACKUP_TIME) {
                                    backupSummaries[app.packageName]?.latest?.time
                                        ?.takeIf { it > 0 }
                                        ?.let { "备份于 ${formatShortDateTime(it)}" }
                                } else {
                                    null
                                },
                                shape = shape,
                                trailingContent = userBadge,
                                onClick = {
                                    selectedApp = app
                                    selectedPackage = app.packageName
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 应用列表页的层级：列表 / 应用详情 / 新建备份 / 管理备份。 */
private enum class AppRoute { LIST, DETAIL, NEW_BACKUP, MANAGE_BACKUP, SETTINGS, ERROR_LOG }

@Composable
private fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "正在读取应用列表…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ErrorState(
    modifier: Modifier = Modifier,
    message: String,
    onRetry: () -> Unit
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "读取应用列表失败",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
