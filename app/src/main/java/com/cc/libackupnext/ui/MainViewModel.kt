package com.cc.libackupnext.ui

import android.app.Application
import android.content.pm.UserInfo
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cc.libackupnext.data.AppPrefs
import com.cc.libackupnext.data.AppBackupSummary
import com.cc.libackupnext.entity.AppInfo
import com.cc.libackupnext.service.AppManagerService
import com.cc.libackupnext.service.RusticService
import com.cc.libackupnext.util.ErrorLog
import com.cc.libackupnext.ui.util.AppIconStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SortType { NAME, INSTALL_TIME, UPDATE_TIME, BACKUP_TIME }

class MainViewModel(application: Application) : AndroidViewModel(application) {

    // ==================== 应用数据 ====================
    private val _allApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val allApps: StateFlow<List<AppInfo>> = _allApps.asStateFlow()

    private val _displayApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val displayApps: StateFlow<List<AppInfo>> = _displayApps.asStateFlow()

    // ==================== 加载状态 ====================
    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /** 下拉刷新中（只驱动顶部指示器，不盖全屏 loading）。 */
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /** 当前 [_allApps] 属于哪个用户，用于切换用户时判断数据是否已就绪。 */
    private val _loadedUserId = MutableStateFlow<Int?>(null)
    val loadedUserId: StateFlow<Int?> = _loadedUserId.asStateFlow()

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    private var loadJob: Job? = null
    private var sortJob: Job? = null

    /** 上次列表加载完成的时间（elapsedRealtime），前台刷新用来判断是否刚拉过。 */
    private var lastLoadAt = 0L

    // ==================== UI 状态 ====================
    var isSearching = MutableStateFlow(false)
    var searchQuery = MutableStateFlow("")
    var isSortMenuExpanded = MutableStateFlow(false)
    var isMoreMenuExpanded = MutableStateFlow(false)

    var sortType = MutableStateFlow(loadSortType())
    var isReverse = MutableStateFlow(AppPrefs.sortReverse)
    var showSystemApps = MutableStateFlow(AppPrefs.showSystemApps)

    // ==================== 用户列表 ====================
    private val _users = MutableStateFlow<List<UserInfo>>(emptyList())
    val users: StateFlow<List<UserInfo>> = _users.asStateFlow()

    var isUserMenuExpanded = MutableStateFlow(false)
    var currentUserId = MutableStateFlow(0)

    // ==================== 滚动触发器 ====================
    private val _scrollToTopTrigger = MutableStateFlow(0)
    val scrollToTopTrigger: StateFlow<Int> = _scrollToTopTrigger.asStateFlow()

    fun requestScrollToTop() {
        _scrollToTopTrigger.update { it + 1 }
    }

    // ==================== init ====================
    init {
        // 用户列表和应用列表互不依赖，并行加载
        loadUsers()
        loadApps()
    }

    // ==================== 用户 ====================
    fun loadUsers() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                try {
                    AppManagerService.getUsers()
                } catch (t: Throwable) {
                    ErrorLog.e(TAG, "loadUsers failed", t)
                    emptyList()
                }
            }
            _users.value = list
        }
    }

    /**
     * 新建备份页的勾选状态：包名 -> 勾选的类型。
     *
     * 放在 ViewModel 里，进出应用详情页不会丢；和管理页各自独立。
     */
    val createSelection = androidx.compose.runtime.mutableStateMapOf<String, Set<String>>()

    /** 管理备份页的勾选状态，和新建页互不影响。 */
    val manageSelection = androidx.compose.runtime.mutableStateMapOf<String, Set<String>>()

    /** 默认勾选只做一次：用户手动取消后，再进页面不会又被全选。 */
    var createSelectionInitialized = false
    var manageSelectionInitialized = false

    /** 从详情页选中某条备份返回管理页时，只勾这一个应用（消费一次就清掉）。 */
    var pendingSingleSelection: Pair<String, Set<String>>? = null

    /** 批量页上次的备份概况缓存：返回页面时先拿它渲染，列表不会先空一帧。 */
    var batchOverviewCache: Map<String, com.cc.libackupnext.data.AppBackupSummary> = emptyMap()

    /** 包名 -> 用户在详情页里明确选中的那条备份；管理页展示它的时间和备注。 */
    val selectedEntries = androidx.compose.runtime.mutableStateMapOf<String, com.cc.libackupnext.data.BackupEntry>()

    // ==================== 应用 ====================
    /**
     * 读取应用列表。
     *
     * @param scrollToTop 读完后回到列表顶部
     * @param fromPullRefresh 下拉刷新：只用顶部指示器，不盖全屏 loading
     * @param silent 静默刷新：不碰 loading / 错误状态，数据没变时连列表都不重组（回前台用）
     */
    fun loadApps(
        scrollToTop: Boolean = true,
        fromPullRefresh: Boolean = false,
        silent: Boolean = false
    ) {
        val userId = currentUserId.value
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (!silent) {
                _loadError.value = null
                // 下拉刷新只用顶部指示器，不要盖住列表的全屏 loading
                _isLoading.value = !fromPullRefresh
                _isRefreshing.value = fromPullRefresh
            }
            // 下拉刷新是用户主动要最新数据：清掉图标失败记录（快照本来就是实时读的）
            if (fromPullRefresh) {
                AppIconStore.invalidateMissing()
            }

            try {
                // 已装应用和仓库里的备份同时查，省一次等待
                val installedDeferred = async(Dispatchers.IO) {
                    AppManagerService.getInstalledAppsAsUser(userId)
                }
                // 备份概况只查一次：既用来补「只在备份里」的应用，也用来算备份数
                val overviewDeferred = async(Dispatchers.IO) {
                    RusticService.repoOverview()
                }

                // 先渲染已安装应用，备份里独有的应用到了再追加。
                // 静默刷新不走这一步：分两次写列表会闪，等最终结果一次写进去。
                val installed = installedDeferred.await()
                Log.i(TAG, "loadApps: userId=$userId, count=${installed.size}, silent=$silent")
                if (userId != currentUserId.value) return@launch

                if (!silent) {
                    _allApps.value = installed
                    _displayApps.value = withContext(Dispatchers.Default) { computeDisplay(installed) }
                    _loadedUserId.value = userId
                    if (scrollToTop) requestScrollToTop()
                }

                val overview = overviewDeferred.await()
                val installedNames = installed.mapTo(HashSet()) { it.packageName }
                val merged = installed + RusticService.backupApps(userId, overview)
                    .filter { it.packageName !in installedNames }
                if (userId != currentUserId.value) return@launch

                if (silent) {
                    _loadedUserId.value = userId
                    // 只有当列表不一样的情况下才更新
                    if (merged != _allApps.value) {
                        _allApps.value = merged
                        _displayApps.value = withContext(Dispatchers.Default) { computeDisplay(merged) }
                    }
                    // 列表里要显示「x个备份」，顺便给「备份时间」排序准备数据
                    loadBackupSummaries(merged, overview)
                    return@launch
                }

                _allApps.value = merged
                _displayApps.value = withContext(Dispatchers.Default) { computeDisplay(merged) }

                // 列表里要显示「x个备份」，顺便给「备份时间」排序准备数据
                loadBackupSummaries(merged, overview)
            } catch (t: CancellationException) {
                // 被新的加载任务取代，正常流程
                throw t
            } catch (t: Throwable) {
                ErrorLog.e(TAG, "loadApps failed", t)
                // 静默刷新失败就保持原样，不弹错误页
                if (isActive && !silent) _loadError.value = t.message ?: "读取应用列表失败"
            } finally {
                // 被新的加载任务取代时不要覆盖它刚设置的 loading 状态
                if (isActive) {
                    lastLoadAt = SystemClock.elapsedRealtime()
                    if (!silent) {
                        _isLoading.value = false
                        _isRefreshing.value = false
                    }
                }
            }
        }
    }

    /** 下拉刷新：保留滚动位置，只用顶部指示器。 */
    fun refresh() = loadApps(scrollToTop = false, fromPullRefresh = true)

    /**
     * 回到前台，静默刷新应用列表，获取这期间的新装 / 卸载。
     */
    fun refreshOnForeground() {
        // 已经有一次加载在跑（冷启动那次），别重复发一轮 IPC
        if (loadJob?.isActive == true) return

        val needsLoad = _allApps.value.isEmpty() || _loadError.value != null
        // 刚加载完的一小段时间内跳过：进主页面那一帧也会收到 ON_RESUME
        if (!needsLoad &&
            SystemClock.elapsedRealtime() - lastLoadAt < FOREGROUND_SKIP_INTERVAL_MS
        ) {
            return
        }
        loadApps(scrollToTop = false, silent = !needsLoad)
    }

    // ==================== 备份概况 ====================

    private val _backupSummaries = MutableStateFlow<Map<String, AppBackupSummary>>(emptyMap())

    /** 包名 -> 备份概况（备份次数 / 最近一次备份时间），列表里显示「x个备份」用。 */
    val backupSummaries: StateFlow<Map<String, AppBackupSummary>> = _backupSummaries

    private suspend fun loadBackupSummaries(
        apps: List<AppInfo>,
        overview: List<com.cc.libackupnext.data.RepoOverview>? = null
    ) {
        val names = apps.associate { it.packageName to it.name }
        val summaries = runCatching { RusticService.backupOverview(names, overview) }
            .getOrDefault(emptyList())
            .associateBy { it.packageName }

        if (summaries == _backupSummaries.value) return
        _backupSummaries.value = summaries

        // 正在按备份时间排序时，数据到了要重排一次
        if (sortType.value == SortType.BACKUP_TIME) {
            _displayApps.value = withContext(Dispatchers.Default) { computeDisplay(_allApps.value) }
        }
    }

    /**
     * 备份 / 覆盖之后刷新：只重读备份概况，不重新查询已安装应用。
     *
     * 重新 loadApps 会把 300+ 个应用的包名、名称、版本全量走一遍 IPC，
     * 而备份并不会改变「装了哪些应用」，没必要付这个成本。
     */
    fun refreshBackupInfo() {
        viewModelScope.launch {
            loadBackupSummaries(_allApps.value)
            _displayApps.value = withContext(Dispatchers.Default) { computeDisplay(_allApps.value) }
        }
    }

    // ==================== 过滤 / 搜索 / 排序 ====================
    fun applyFiltersAndSort() {
        persistSortOptions()
        sortJob?.cancel()
        sortJob = viewModelScope.launch {
            val filtered = withContext(Dispatchers.Default) { computeDisplay(_allApps.value) }
            _displayApps.value = filtered
            requestScrollToTop()
        }
    }

    /** 记住排序按钮的勾选情况。 */
    private fun persistSortOptions() {
        AppPrefs.sortType = sortType.value.name
        AppPrefs.sortReverse = isReverse.value
        AppPrefs.showSystemApps = showSystemApps.value
    }

    private fun computeDisplay(source: List<AppInfo>): List<AppInfo> {
        var list = source

        if (!showSystemApps.value) {
            list = list.filter { !it.isSystem }
        }

        val query = searchQuery.value
        if (query.isNotBlank()) {
            list = list.filter {
                it.name.contains(query, ignoreCase = true) ||
                        it.packageName.contains(query, ignoreCase = true)
            }
        }

        list = when (sortType.value) {
            SortType.NAME -> list.sortedBy { it.name.lowercase() }
            SortType.INSTALL_TIME -> list.sortedBy { it.installTime }
            SortType.UPDATE_TIME -> list.sortedBy { it.updateTime }
            // 备份时间默认「最近的在前」，勾了倒序才是最早的在前
            SortType.BACKUP_TIME -> list.sortedByDescending {
                _backupSummaries.value[it.packageName]?.latest?.time ?: 0L
            }
        }
        if (isReverse.value) list = list.asReversed()

        return list
    }

    // ==================== 搜索操作 ====================
    fun updateSearchQuery(query: String) {
        searchQuery.value = query
        applyFiltersAndSort()
    }

    fun toggleSearch() {
        isSearching.value = !isSearching.value
        if (!isSearching.value) {
            searchQuery.value = ""
            applyFiltersAndSort()
        }
    }

    companion object {
        private const val TAG = "MainViewModel"

        /** 距上次加载不足这个时间就不再做前台刷新（进页面那一下的 ON_RESUME）。 */
        private const val FOREGROUND_SKIP_INTERVAL_MS = 1_000L

        private fun loadSortType(): SortType =
            runCatching { SortType.valueOf(AppPrefs.sortType) }.getOrDefault(SortType.NAME)
    }
}
