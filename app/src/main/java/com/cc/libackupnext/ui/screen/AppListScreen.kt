package com.cc.libackupnext.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.snapshotFlow
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cc.libackupnext.data.AppPrefs
import com.cc.libackupnext.data.BackupTarget
import com.cc.libackupnext.data.backupDirs
import com.cc.libackupnext.entity.AppInfo
import com.cc.libackupnext.entity.dirAppInfo
import com.cc.libackupnext.entity.isDirectory
import com.cc.libackupnext.ui.AppBottomBar
import com.cc.libackupnext.ui.AppTopBar
import com.cc.libackupnext.ui.MainTab
import com.cc.libackupnext.ui.MainViewModel
import com.cc.libackupnext.ui.SortType
import com.cc.libackupnext.ui.UserBadge
import com.cc.libackupnext.ui.isNonPrimaryUser
import com.cc.libackupnext.ui.theme.pageTransitionSpec
import com.cc.libackupnext.ui.util.AppItemCard
import com.cc.libackupnext.ui.util.FolderIconBox
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
    val searchQueryText by viewModel.searchQuery.collectAsState()
    val scrollTrigger by viewModel.scrollToTopTrigger.collectAsState()

    var selectedApp by remember { mutableStateOf<AppInfo?>(null) }
    // 记住打开的是哪个包，进程被回收后还能回到这个页面
    var selectedPackage by rememberSaveable { mutableStateOf<String?>(null) }
    // 新建应用备份页
    var showNewBackup by rememberSaveable { mutableStateOf(false) }
    // 管理应用备份页
    var showManageBackup by rememberSaveable { mutableStateOf(false) }
    // 新建文件备份页
    var showNewFileBackup by rememberSaveable { mutableStateOf(false) }
    // 管理文件备份页
    var showManageFileBackup by rememberSaveable { mutableStateOf(false) }
    // 设置页
    var showSettings by rememberSaveable { mutableStateOf(false) }
    // 错误日志页
    var showErrorLog by rememberSaveable { mutableStateOf(false) }
    // 详情页是从哪个批量页进来的：返回时回到原页面
    var detailFrom by remember { mutableStateOf<AppRoute?>(null) }
    // 批量页列表的滚动位置：页面被重建时还能回到原来的位置
    val batchListState = rememberLazyListState()

    // 底栏：应用 / 文件。不持久化，每次进应用都先落在「应用」页
    var tab by remember { mutableStateOf(MainTab.APPS) }
    // 文件页要备份的目录：默认 4 个 + 用户自己加的
    var extraDirs by remember { mutableStateOf(AppPrefs.extraDirs) }
    val fileDirs = remember(extraDirs) { backupDirs(extraDirs) }
    val fileListState = rememberLazyListState()
    // 文件页的刷新信号：回到前台时递增，让它重新判断哪些目录还在硬盘上
    var dirRefreshTick by remember { mutableStateOf(0) }


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

    // 每次回到前台都静默重读一次应用列表：这期间可能装了新应用、卸了旧应用。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshOnForeground()
                // 文件页也要重算：用户可能在别的应用里删了/建了目录
                dirRefreshTick++
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

    // 往下滑列表时把底栏收起来（向下移出），往上滑或到顶再出来
    val activeListState = if (tab == MainTab.APPS) listState else fileListState
    var lastScrollPos by remember { mutableStateOf(0) }
    var barVisible by remember { mutableStateOf(true) }
    LaunchedEffect(activeListState) {
        snapshotFlow {
            activeListState.firstVisibleItemIndex * 100_000 +
                    activeListState.firstVisibleItemScrollOffset
        }.collect { pos ->
            if (pos > lastScrollPos + 24) {
                barVisible = false
            } else if (pos < lastScrollPos) {
                barVisible = true
            }
            lastScrollPos = pos
        }
    }

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

    BackHandler(enabled = showNewFileBackup) { showNewFileBackup = false }
    BackHandler(enabled = showManageFileBackup) { showManageFileBackup = false }

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
        showNewFileBackup -> AppRoute.NEW_FILE_BACKUP
        showManageFileBackup -> AppRoute.MANAGE_FILE_BACKUP
        showNewBackup -> AppRoute.NEW_BACKUP
        showManageBackup -> AppRoute.MANAGE_BACKUP
        showSettings -> AppRoute.SETTINGS
        showErrorLog -> AppRoute.ERROR_LOG
        selectedApp != null -> AppRoute.DETAIL
        else -> AppRoute.LIST
    }

    AnimatedContent(
        targetState = route,
        // 层级越深越靠右：进入时从右滑入，返回时反向
        transitionSpec = pageTransitionSpec { it.ordinal },
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
                    target = if (current.isDirectory) BackupTarget.DIR else BackupTarget.APP,
                    // 文件夹图标由列表页统一提供，详情页不再自己画一套
                    iconContent = if (current.isDirectory) {
                        { FolderIconBox() }
                    } else {
                        null
                    },
                    // 默认目录也在 extraDirs 里，所以移除逻辑只有一种：从集合里删掉
                    onRemoveDir = if (current.isDirectory) {
                        {
                            val next = AppPrefs.extraDirs - current.packageName
                            AppPrefs.extraDirs = next
                            extraDirs = next
                            selectedApp = null
                            selectedPackage = null
                        }
                    } else {
                        null
                    },
                    onBack = {
                        selectedApp = null
                        selectedPackage = null
                        when (detailFrom) {
                            AppRoute.NEW_BACKUP -> showNewBackup = true
                            AppRoute.MANAGE_BACKUP -> showManageBackup = true
                            AppRoute.NEW_FILE_BACKUP -> showNewFileBackup = true
                            AppRoute.MANAGE_FILE_BACKUP -> showManageFileBackup = true
                            else -> Unit
                        }
                        detailFrom = null
                    },
                    // 只有从管理备份页进来才「点备份 = 选中并返回」，
                    // 从应用列表进来点卡片还是改备注
                    // 从「管理备份」页进来的：点列表就是选中这条备份并返回原页面。
                    // 应用备份和文件备份走同一套逻辑，只是退回去的页面不同。
                    onSelectBackup = when (detailFrom) {
                        AppRoute.MANAGE_BACKUP, AppRoute.MANAGE_FILE_BACKUP -> { entry ->
                            val backToFile = detailFrom == AppRoute.MANAGE_FILE_BACKUP
                            viewModel.pendingSingleSelection =
                                current.packageName to entry.availableKinds.toSet()
                            viewModel.selectedEntries[current.packageName] = entry
                            selectedApp = null
                            selectedPackage = null
                            detailFrom = null
                            if (backToFile) showManageFileBackup = true else showManageBackup = true
                        }

                        else -> null
                    }
                )
            }

            AppRoute.NEW_FILE_BACKUP -> AppBackupScreen(
                viewModel = viewModel,
                onBack = { showNewFileBackup = false },
                onSwitchMode = {
                    showNewFileBackup = false
                    showManageFileBackup = true
                },
                onOpenAppDetail = { app ->
                    detailFrom = AppRoute.NEW_FILE_BACKUP
                    showNewFileBackup = false
                    selectedApp = app
                    selectedPackage = app.packageName
                },
                listState = batchListState,
                target = BackupTarget.DIR
            )

            AppRoute.MANAGE_FILE_BACKUP -> ManageBackupScreen(
                viewModel = viewModel,
                onBack = { showManageFileBackup = false },
                onSwitchMode = {
                    showManageFileBackup = false
                    showNewFileBackup = true
                },
                onOpenAppDetail = { app ->
                    detailFrom = AppRoute.MANAGE_FILE_BACKUP
                    showManageFileBackup = false
                    selectedApp = app
                    selectedPackage = app.packageName
                },
                listState = batchListState,
                target = BackupTarget.DIR
            )

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
                        // 文件页不显示用户切换：文件夹备份只做主用户
                        showUserButton = tab == MainTab.APPS,
                        // 两个页签搜的东西不一样，占位文案跟着换
                        searchPlaceholder = if (tab == MainTab.FILES) {
                            "搜索文件夹路径或名称"
                        } else {
                            "搜索应用名称或包名"
                        },
                        pageBackground = pageBackground,
                        searchBarBackground = searchBarBackground,
                        onCreateBackup = {
                            if (isSearching) viewModel.toggleSearch()
                            if (tab == MainTab.FILES) showNewFileBackup = true else showNewBackup = true
                        },
                        onManageBackup = {
                            if (isSearching) viewModel.toggleSearch()
                            if (tab == MainTab.FILES) showManageFileBackup = true else showManageBackup = true
                        },
                        createBackupLabel = if (tab == MainTab.FILES) "新建文件备份" else "新建应用备份",
                        manageBackupLabel = if (tab == MainTab.FILES) "管理文件备份" else "管理应用备份",
                        onOpenSettings = {
                            if (isSearching) viewModel.toggleSearch()
                            showSettings = true
                        },
                        onOpenErrorLog = {
                            if (isSearching) viewModel.toggleSearch()
                            showErrorLog = true
                        },
                        // 文件页的「重新加载」重算目录列表，顺手刷新备份数量
                        onReload = if (tab == MainTab.FILES) {
                            {
                                dirRefreshTick++
                                viewModel.loadApps()
                            }
                        } else {
                            null
                        }
                    )
                }
            ) { paddingValues ->
                Box(modifier = Modifier.fillMaxSize()) {
                AnimatedContent(
                    targetState = tab,
                    // 左右方向：切到文件页从右边进，切回应用页从左边进
                    transitionSpec = pageTransitionSpec<MainTab> { it.ordinal },
                    label = "ListTab"
                ) { current ->
                    if (current == MainTab.FILES) {
                        FileListScreen(
                            dirs = fileDirs,
                            refreshTick = dirRefreshTick,
                            query = searchQueryText,
                            summaries = backupSummaries,
                            listState = fileListState,
                            onOpen = { path ->
                                selectedApp = dirAppInfo(path)
                                selectedPackage = path
                            },
                            onAdd = { path ->
                                val next = AppPrefs.extraDirs + path
                                AppPrefs.extraDirs = next
                                extraDirs = next
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(paddingValues)
                        )
                    } else {
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
                                // 底部给悬浮底栏留空间，最后一条能滚到它上面
                                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
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
                    AnimatedVisibility(
                        visible = barVisible,
                        enter = slideInVertically(tween(200)) { it },
                        exit = slideOutVertically(tween(200)) { it },
                        modifier = Modifier.align(Alignment.BottomCenter)
                    ) {
                    AppBottomBar(
                        selected = tab,
                        onSelect = {
                            if (isSearching) viewModel.toggleSearch()
                            tab = it
                        },
                        containerColor = if (isDark) {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        } else {
                            Color(0xFFF2F3F7)
                        },
                    )
                    }
                }
            }
        }
    }
}

/** 应用列表页的层级：列表 / 应用详情 / 新建备份 / 管理备份。 */
private enum class AppRoute {
    LIST, DETAIL, NEW_BACKUP, MANAGE_BACKUP, SETTINGS, ERROR_LOG, NEW_FILE_BACKUP, MANAGE_FILE_BACKUP
}

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
