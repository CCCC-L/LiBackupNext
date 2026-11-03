package com.cc.libackupnext.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cc.libackupnext.data.AppPrefs
import com.cc.libackupnext.data.BackupTarget
import com.cc.libackupnext.entity.AppInfo
import com.cc.libackupnext.entity.dirAppInfo
import com.cc.libackupnext.ui.AppBottomBar
import com.cc.libackupnext.ui.AppTopBar
import com.cc.libackupnext.ui.HomeTab
import com.cc.libackupnext.ui.MainViewModel
import com.cc.libackupnext.ui.SortType
import com.cc.libackupnext.ui.theme.pageTransitionSpec
import com.cc.libackupnext.ui.util.countText
import com.cc.libackupnext.ui.util.formatShortDateTime
import com.cc.libackupnext.ui.util.systemCount

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

    // ==================== 页面栈 ====================
    var route by rememberSaveable { mutableStateOf(AppRoute.LIST) }
    // 批量页的两个参数：模式 + 从哪个页签进来的（页签决定要列哪些目标）。
    // 以前是「一种组合一个布尔」，四种组合四个变量
    var batchMode by rememberSaveable { mutableStateOf(BackupBatchMode.CREATE) }
    var batchTab by rememberSaveable { mutableStateOf(HomeTab.APPS) }
    // 详情页是从批量页进来的吗？决定返回时回批量页还是列表页
    var detailFromBatch by rememberSaveable { mutableStateOf(false) }

    var selectedApp by remember { mutableStateOf<AppInfo?>(null) }
    // 记住打开的是哪个包，进程被回收后还能回到这个页面
    var selectedPackage by rememberSaveable { mutableStateOf<String?>(null) }
    // 批量页列表的滚动位置：页面被重建时还能回到原来的位置
    val batchListState = rememberLazyListState()

    // 底栏：应用 / 文件 / 设置。不持久化，每次进应用都先落在「应用」页
    var tab by remember { mutableStateOf(HomeTab.APPS) }
    // 页签的横向 Pager：手势、跟手、相邻页都由它负责
    val pagerState = rememberPagerState(initialPage = tab.ordinal) { HomeTab.entries.size }

    // 点底栏切页签 → 滚到对应页
    LaunchedEffect(tab) {
        if (pagerState.currentPage != tab.ordinal) {
            pagerState.animateScrollToPage(tab.ordinal)
        }
    }

    // 手滑停在某一页 → 同步回 tab（顶部标题、搜索占位都跟着变）
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            val target = HomeTab.entries[page]
            if (target != tab) {
                if (isSearching) viewModel.toggleSearch()
                tab = target
            }
        }
    }
    // 文件页要备份的目录：默认 4 个 + 用户自己加的，AppPrefs 读出来就是字母序
    var extraDirs by remember { mutableStateOf(AppPrefs.extraDirs) }
    val fileDirs = extraDirs.toList()
    val fileListState = rememberLazyListState()
    val othersListState = rememberLazyListState()
    // 文件页的刷新信号：回到前台 / 主动刷新时递增，让它重新判断哪些目录还在硬盘上
    val dirRefreshTick by viewModel.dirRefreshTick.collectAsState()


    val allApps by viewModel.allApps.collectAsState()
    val currentUserId by viewModel.currentUserId.collectAsState()
    val systemCounts by viewModel.systemDataCounts.collectAsState()

    // 切换用户 / 进程恢复时，保持当前打开的页面，只把 userId 换成新用户
    LaunchedEffect(currentUserId, allApps, selectedPackage) {
        val pkg = selectedPackage ?: return@LaunchedEffect
        if (selectedApp?.packageName == pkg && selectedApp?.userId == currentUserId) {
            return@LaunchedEffect
        }

        val found = allApps.firstOrNull { it.packageName == pkg && it.userId == currentUserId }
        selectedApp = found ?: selectedApp?.takeIf { it.packageName == pkg }?.copy(userId = currentUserId)
    }

    /**
     * 回到前台时统一刷一遍。
     *
     * 三块数据（应用列表 / 目录存在性 / 「其他」页条数）的数据源不同，但触发时机一样，
     * 所以统一收在 ViewModel 的 [MainViewModel.refreshOnForeground] 里。
     */
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshOnForeground()
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

    // 往下滑列表时把底栏收起来（向下移出），往上滑或到顶再出来。
    // 设置页不是列表，没有滚动状态可跟，底栏一直显示。
    val activeListState = when (tab) {
        HomeTab.APPS -> listState
        HomeTab.FILES -> fileListState
        HomeTab.OTHERS -> othersListState
        HomeTab.SETTINGS -> null
    }
    var lastScrollPos by remember { mutableStateOf(0) }
    var barVisible by remember { mutableStateOf(true) }
    LaunchedEffect(activeListState) {
        val state = activeListState
        if (state == null) {
            barVisible = true
            return@LaunchedEffect
        }
        snapshotFlow {
            state.firstVisibleItemIndex * 100_000 + state.firstVisibleItemScrollOffset
        }.collect { pos ->
            if (pos > lastScrollPos + 24) {
                barVisible = false
            } else if (pos < lastScrollPos) {
                barVisible = true
            }
            lastScrollPos = pos
        }
    }

    // 切到「其他」页、或者从详情页退回来时重新数一遍：
    // 条数是别的应用随时会改的数据，不能只信进应用时那次
    LaunchedEffect(tab, route) {
        if (tab == HomeTab.OTHERS && route == AppRoute.LIST) {
            viewModel.refreshSystemData()
        }
    }

    // 滚动触发器变化 → 回到顶部
    LaunchedEffect(scrollTrigger) {
        if (scrollTrigger > 0) {
            listState.scrollToItem(0)
        }
    }

    // ==================== 导航 ====================
    fun openBatch(mode: BackupBatchMode, from: HomeTab) {
        if (isSearching) viewModel.toggleSearch()
        batchMode = mode
        batchTab = from
        route = AppRoute.BATCH
    }

    fun openDetail(app: AppInfo, fromBatch: Boolean) {
        selectedApp = app
        selectedPackage = app.packageName
        detailFromBatch = fromBatch
        route = AppRoute.DETAIL
    }

    fun closeDetail() {
        selectedApp = null
        selectedPackage = null
        route = if (detailFromBatch) AppRoute.BATCH else AppRoute.LIST
        detailFromBatch = false
    }

    // 搜索优先响应返回
    BackHandler(enabled = isSearching) {
        viewModel.toggleSearch()
    }

    // 批量页 / 错误日志：返回列表页。详情页自己处理返回（onBack）
    BackHandler(enabled = route == AppRoute.BATCH || route == AppRoute.ERROR_LOG) {
        route = AppRoute.LIST
    }

    // 页面内容单独记一份：切用户时 AppInfo 变了但页面不变，避免重放进入动画
    var pageApp by remember { mutableStateOf<AppInfo?>(null) }
    LaunchedEffect(selectedApp) {
        if (selectedApp != null) pageApp = selectedApp
    }

    AnimatedContent(
        targetState = route,
        // 层级越深越靠右：进入时从右滑入，返回时反向
        transitionSpec = pageTransitionSpec { it.depth },
        label = "PageTransition"
    ) { target ->
        when (target) {
            AppRoute.BATCH -> BackupBatchScreen(
                viewModel = viewModel,
                mode = batchMode,
                tab = batchTab,
                onBack = { route = AppRoute.LIST },
                onSwitchMode = {
                    batchMode = if (batchMode == BackupBatchMode.CREATE) {
                        BackupBatchMode.MANAGE
                    } else {
                        BackupBatchMode.CREATE
                    }
                },
                onOpenAppDetail = { app -> openDetail(app, fromBatch = true) },
                listState = batchListState
            )

            AppRoute.DETAIL -> pageApp?.let { current ->
                AppBackupScreen(
                    viewModel = viewModel,
                    app = current,
                    target = BackupTarget.of(current),
                    // 默认目录也在 extraDirs 里，所以移除逻辑只有一种：从集合里删掉
                    onRemoveDir = if (BackupTarget.of(current) == BackupTarget.DIR) {
                        {
                            AppPrefs.extraDirs = AppPrefs.extraDirs - current.packageName
                            // 读回来是排好序的
                            extraDirs = AppPrefs.extraDirs
                            closeDetail()
                        }
                    } else {
                        null
                    },
                    onBack = { closeDetail() },
                    // 从「管理备份」页进来时，点一条备份 = 选中它并返回原页面
                    onSelectBackup = if (detailFromBatch && batchMode == BackupBatchMode.MANAGE) {
                        { entry ->
                            viewModel.pendingSingleSelection =
                                current.packageName to entry.availableKinds.toSet()
                            viewModel.selectedEntries[current.packageName] = entry
                            closeDetail()
                        }
                    } else {
                        null
                    }
                )
            }

            AppRoute.ERROR_LOG -> ErrorLogScreen(
                onBack = { route = AppRoute.LIST }
            )

            AppRoute.LIST -> Scaffold(
                containerColor = pageBackground,

                topBar = {
                    AppTopBar(
                        viewModel = viewModel,
                        title = if (tab == HomeTab.SETTINGS) tab.label else appName,
                        // 用户切换只跟应用有关
                        showUserButton = tab == HomeTab.APPS,
                        // 设置页没有可搜 / 可排序的东西
                        showSearch = tab != HomeTab.SETTINGS,
                        showSortMenu = tab != HomeTab.SETTINGS,
                        searchPlaceholder = tab.searchHint,
                        pageBackground = pageBackground,
                        searchBarBackground = searchBarBackground,
                        // 备份入口只给「备份目标列表」页
                        onCreateBackup = if (tab.isTargetList) {
                            { openBatch(BackupBatchMode.CREATE, tab) }
                        } else {
                            null
                        },
                        onManageBackup = if (tab.isTargetList) {
                            { openBatch(BackupBatchMode.MANAGE, tab) }
                        } else {
                            null
                        },
                        createBackupLabel = tab.createLabel,
                        manageBackupLabel = tab.manageLabel,
                        onOpenErrorLog = {
                            if (isSearching) viewModel.toggleSearch()
                            route = AppRoute.ERROR_LOG
                        },
                        // 文件页的「重新加载」重算目录列表，顺手刷新备份数量
                        onReload = when (tab) {
                            HomeTab.FILES -> {
                                {
                                    viewModel.refreshDirs()
                                    viewModel.loadList()
                                }
                            }

                            HomeTab.OTHERS -> {
                                {
                                    viewModel.refreshSystemData()
                                    viewModel.loadList(scrollToTop = false)
                                }
                            }

                            HomeTab.APPS -> {
                                {
                                    viewModel.loadList()
                                }
                            }

                            // 设置页没有可刷新的内容
                            HomeTab.SETTINGS -> null
                        }
                    )
                }
            ) { paddingValues ->
                Box(modifier = Modifier.fillMaxSize()) {
                    // 页签用 Pager：拖动时当前页跟着手指走，相邻页同时露出来
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        // 预组合左右各一页，慢速拖动时不会先看到白底
                        beyondViewportPageCount = 1
                    ) { page ->
                        when (HomeTab.entries[page]) {
                            HomeTab.FILES -> FileListScreen(
                                dirs = fileDirs,
                                refreshTick = dirRefreshTick,
                                query = searchQueryText,
                                summaries = backupSummaries,
                                listState = fileListState,
                                onOpen = { path ->
                                    openDetail(dirAppInfo(path), fromBatch = false)
                                },
                                onAdd = { path ->
                                    AppPrefs.extraDirs = AppPrefs.extraDirs + path
                                    // 读回来是排好序的
                                    extraDirs = AppPrefs.extraDirs
                                },
                                modifier = Modifier.padding(paddingValues),
                                isRefreshing = isRefreshing,
                                onRefresh = {
                                    // 目录可能在别处被删了：重算一遍存在性，再刷新备份数
                                    viewModel.refreshDirs()
                                    viewModel.refresh()
                                }
                            )

                            HomeTab.OTHERS -> TargetListScreen(
                                // 固定目标永远各出一行，和系统里有没有数据无关
                                rows = tab.targets.flatMap { target ->
                                    target.targets(
                                        emptyList(),
                                        create = true,
                                        hasBackup = { false })
                                        .map { app -> TargetRow(target, app) }
                                },
                                summaries = backupSummaries,
                                listState = othersListState,
                                onOpen = { app -> openDetail(app, fromBatch = false) },
                                modifier = Modifier.padding(paddingValues),
                                isRefreshing = isRefreshing,
                                onRefresh = {
                                    viewModel.refresh()
                                    viewModel.refreshSystemData()
                                },
                                // 副标题是条数：已保存 12 个 / 共 123 个 / 共 86 条
                                subtitleOf = { row ->
                                    row.target.countText(row.target.systemCount(systemCounts))
                                }
                            )

                            HomeTab.SETTINGS -> SettingsContent(
                                // 底部给悬浮底栏留空间，最后一项能滚到它上面
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(paddingValues)
                                    .padding(bottom = 88.dp),
                                onSettingChanged = {
                                    viewModel.loadList(scrollToTop = false)
                                }
                            )

                            HomeTab.APPS -> when {
                                displayApps.isEmpty() && isLoading -> LoadingState(
                                    Modifier
                                        .fillMaxSize()
                                        .padding(paddingValues)
                                )

                                displayApps.isEmpty() && loadError != null -> ErrorState(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(paddingValues),
                                    message = loadError.orEmpty(),
                                    onRetry = { viewModel.loadList() }
                                )

                                else -> TargetListScreen(
                                    rows = displayApps.map { TargetRow(BackupTarget.APP, it) },
                                    summaries = backupSummaries,
                                    listState = listState,
                                    onOpen = { app -> openDetail(app, fromBatch = false) },
                                    modifier = Modifier.padding(paddingValues),
                                    isRefreshing = isRefreshing,
                                    onRefresh = { viewModel.refresh() },
                                    // 按备份时间排序时，再下面显示备份时间；没备份就不显示
                                    extraBottomText = { app ->
                                        if (sortType == SortType.BACKUP_TIME) {
                                            backupSummaries[app.packageName]?.latest?.time
                                                ?.takeIf { it > 0 }
                                                ?.let { "备份于 ${formatShortDateTime(it)}" }
                                        } else {
                                            null
                                        }
                                    }
                                )
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

/**
 * 主页面的页面栈。
 *
 * 底栏的三个页签都在这一个 route 里（[LIST]），切页签不算换页；
 * 只有详情 / 批量备份 / 错误日志才是压在上面的页面。
 *
 * @param depth 转场方向：越深越靠右
 */
private enum class AppRoute(val depth: Int) {
    LIST(0),
    BATCH(1),
    ERROR_LOG(1),
    DETAIL(2)
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
