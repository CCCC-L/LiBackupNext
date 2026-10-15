package com.cc.libackupnext.ui.screen

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.net.Uri
import android.os.UserHandle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.cc.libackupnext.data.BackupEntry
import com.cc.libackupnext.data.BackupStep
import com.cc.libackupnext.entity.AppInfo
import com.cc.libackupnext.service.AppManagerService
import com.cc.libackupnext.service.BackupProgress
import com.cc.libackupnext.service.RusticService
import com.cc.libackupnext.util.BackupNotifier
import com.cc.libackupnext.util.ErrorLog
import com.cc.libackupnext.util.KeepAwake
import com.cc.libackupnext.ui.dialog.AppAction
import com.cc.libackupnext.ui.util.AppIcon
import com.cc.libackupnext.ui.util.AppIconStore
import com.cc.libackupnext.ui.util.AppItemCard
import com.cc.libackupnext.ui.util.AppListContainer
import com.cc.libackupnext.ui.dialog.AppOptionsMenu
import com.cc.libackupnext.ui.AppTopBar
import com.cc.libackupnext.ui.dialog.BackupHelpDialog
import com.cc.libackupnext.ui.dialog.BackupProgressDialog
import com.cc.libackupnext.ui.util.FrozenSnowflake
import com.cc.libackupnext.ui.MainViewModel
import com.cc.libackupnext.ui.dialog.ProgressSection
import com.cc.libackupnext.ui.UserBadge
import com.cc.libackupnext.ui.util.formatDateTime
import com.cc.libackupnext.ui.util.formatSize
import com.cc.libackupnext.ui.isNonPrimaryUser
import com.cc.libackupnext.ui.util.rememberAppIcon
import com.cc.libackupnext.ui.util.rememberBackupIcon
import com.cc.libackupnext.ui.dialog.rememberDoubleClickGuard
import com.cc.libackupnext.ui.theme.LocalAppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBackupScreen(
    viewModel: MainViewModel,
    app: AppInfo,
    onBack: () -> Unit,
    /**
     * 从管理备份页进来时传：点某条备份就选中它并返回管理页。
     * 传 null（从应用列表进来）则点卡片仍是修改备注。
     */
    onSelectBackup: ((BackupEntry) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 系统返回键回到上一页，而不是退出应用
    BackHandler(onBack = onBack)

    val isDark = isSystemInDarkTheme()
    val pageBackground =
        if (isDark) MaterialTheme.colorScheme.surfaceContainerLow else Color(0xFFEAEEF7)
    val searchBarBackground =
        if (isDark) MaterialTheme.colorScheme.surfaceContainerHighest else Color(0xFFE2E7F1)

    // 帮助对话框显示状态
    var showHelpDialog by remember { mutableStateOf(false) }

    // ==================== 应用状态 ====================
    val currentUserId by viewModel.currentUserId.collectAsState()
    val allApps by viewModel.allApps.collectAsState()
    val loadedUserId by viewModel.loadedUserId.collectAsState()

    // 切换用户后按包名重新匹配当前用户下的应用（列表数据就绪后才判断，避免闪烁）
    val appForCurrentUser = if (loadedUserId == currentUserId) {
        allApps.firstOrNull {
            it.packageName == app.packageName && it.userId == currentUserId
        }
    } else {
        null
    }

    // 页面展示的应用：优先当前用户下的同名应用，切换用户后名称/版本/图标都会跟着变。
    // 当前用户没装这个应用时退回原数据，但 userId 必须用当前选中的用户，
    // 否则安装/恢复会继续作用在切换前的那个用户上。
    val displayApp = (appForCurrentUser ?: app).copy(userId = currentUserId)

    // 冻结状态直接来自列表数据（ApplicationInfo.enabled），不再单独查询
    var isFrozen by remember(displayApp.packageName, displayApp.userId) {
        mutableStateOf(displayApp.isFrozen)
    }
    // 手动卸载后列表里就没有它了，页面内先记住；切换用户时重新判定
    var forceUninstalled by remember(app.packageName) { mutableStateOf(false) }
    // 恢复把 apk 装上了：本地先按已安装显示，不用等列表重读
    // （应用名 / 版本 / 包名本来就来自备份快照，这里只翻转「是否已安装」这一位）
    var restoredInstalled by remember(app.packageName, currentUserId) { mutableStateOf(false) }
    LaunchedEffect(currentUserId) {
        forceUninstalled = false
        restoredInstalled = false
    }

    // 已卸载的两种情形：
    // 1. 当前用户下根本没有这个应用（例如切换到了未安装该应用的用户）；
    // 2. 只在仓库里有备份（列表里那条 onlyInBackup 记录）—— 列表按它置灰，这里必须一致。
    val isUninstalled = !restoredInstalled &&
            (forceUninstalled ||
                    displayApp.onlyInBackup ||
                    (loadedUserId == currentUserId && appForCurrentUser == null))

    var isBusy by remember { mutableStateOf(false) }
    var showOptions by remember { mutableStateOf(false) }
    var confirmAction by remember { mutableStateOf<AppAction?>(null) }

    // ==================== 备份 ====================
    var showBackupOptions by remember { mutableStateOf(false) }
    var backupApk by remember { mutableStateOf(true) }
    var backupUser by remember { mutableStateOf(true) }
    var backupData by remember { mutableStateOf(true) }
    var showBackupProgress by remember { mutableStateOf(false) }
    var backupRunning by remember { mutableStateOf(false) }

    // 页面被销毁时别把前台服务和通知留在通知栏
    DisposableEffect(Unit) {
        onDispose { if (backupRunning) BackupNotifier.cancel(context) }
    }
    var progressTitle by remember { mutableStateOf("正在备份") }
    var backupError by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<BackupEntry>>(emptyList()) }

    // 应用信息页用自己的搜索状态，不影响 applist 的搜索框
    var detailSearchOpen by remember { mutableStateOf(false) }
    var detailQuery by remember { mutableStateOf("") }

    // 列表行统一用这套颜色（在列表外读一次，避免 lazy item 里读到不同的值）
    val subtitleColor = LocalAppColors.current.subtitleColor
    val bottomTextColor = LocalAppColors.current.bottomTextColor

    // 应用信息页的搜索只过滤备注
    val visibleEntries = remember(entries, detailQuery) {
        val query = detailQuery.trim()
        if (query.isEmpty()) {
            entries
        } else {
            entries.filter { it.description?.contains(query, ignoreCase = true) == true }
        }
    }
    var reloadKey by remember { mutableStateOf(0) }
    var editingEntry by remember { mutableStateOf<BackupEntry?>(null) }
    var managingEntry by remember { mutableStateOf<BackupEntry?>(null) }
    var managingKinds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmDeleteAll by remember { mutableStateOf(false) }

    // 覆盖备份 / 删除需要双击确认
    val doubleClickGuard = rememberDoubleClickGuard()
    var noteInput by remember { mutableStateOf("") }
    val backupSteps = remember { MutableStateFlow<List<BackupStep>>(emptyList()) }
    val currentSteps by backupSteps.collectAsState()

    val isInstalled = !isUninstalled
    val canInteract = isInstalled && !isBusy

    // 执行 root 侧操作，完成后刷新应用状态
    fun runAction(action: AppAction) {
        if (isBusy) return
        scope.launch {
            isBusy = true
            try {
                withContext(Dispatchers.IO) {
                    when (action) {
                        AppAction.FORCE_STOP ->
                            AppManagerService.forceStop(displayApp.packageName, displayApp.userId)

                        AppAction.FREEZE ->
                            AppManagerService.freeze(displayApp.packageName, displayApp.userId)

                        AppAction.UNFREEZE ->
                            AppManagerService.unFreeze(displayApp.packageName, displayApp.userId)

                        AppAction.CLEAR_DATA ->
                            AppManagerService.clearAppData(displayApp.packageName, displayApp.userId)

                        AppAction.UNINSTALL ->
                            AppManagerService.unInstall(displayApp.packageName, displayApp.userId)

                        else -> Unit
                    }
                }

                // 冻结 / 解冻 / 卸载后刷新应用状态（重拉列表，状态本来就在列表数据里）
                when (action) {
                    AppAction.FREEZE -> {
                        isFrozen = true
                        viewModel.loadApps(scrollToTop = false)
                    }

                    AppAction.UNFREEZE -> {
                        isFrozen = false
                        viewModel.loadApps(scrollToTop = false)
                    }

                    AppAction.UNINSTALL -> {
                        forceUninstalled = true
                        restoredInstalled = false
                        viewModel.loadApps(scrollToTop = false)
                    }

                    else -> Unit
                }

                Toast.makeText(context, "成功", Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                Toast.makeText(
                    context,
                    "${action.title()}失败：${t.message ?: "未知错误"}",
                    Toast.LENGTH_SHORT
                ).show()
            } finally {
                isBusy = false
            }
        }
    }

    /** 备份 / 覆盖备份共用：进度弹窗 + 结果处理，具体逻辑在 service 层。 */
    fun runBackup(kinds: Set<String>, overwrite: BackupEntry? = null) {
        showBackupOptions = false
        managingEntry = null
        backupError = null
        progressTitle = "正在备份"
        backupRunning = true
        showBackupProgress = true
        backupSteps.value = RusticService.backupSteps(kinds)
        val steps = backupSteps.value
        BackupNotifier.start(
            context = context,
            action = "备份",
            appName = displayApp.name,
            packageName = displayApp.packageName,
            userId = displayApp.userId,
            appIndex = 1,
            appTotal = 1
        )

        scope.launch {
            val onProgress: (BackupProgress) -> Unit = { progress ->
                backupSteps.update { RusticService.applyProgress(it, progress) }

                val stepIndex = steps.indexOfFirst { it.key == progress.stage }
                BackupNotifier.update(
                    context = context,
                    action = "备份",
                    appName = displayApp.name,
                    packageName = displayApp.packageName,
                    userId = displayApp.userId,
                    appIndex = 1,
                    appTotal = 1,
                    stageLabel = BackupNotifier.stageLabel(progress.stage),
                    stageIndex = if (stepIndex >= 0) stepIndex + 1 else 1,
                    stageTotal = steps.size
                )
            }

            val result = if (overwrite == null) {
                RusticService.backup(displayApp, kinds, onProgress)
            } else {
                RusticService.overwrite(displayApp, overwrite, kinds, onProgress)
            }

            backupRunning = false
            BackupNotifier.finish(context, "备份", if (result.isFailure) 1 else 0, 1)
            if (result.isFailure) {
                backupError = result.exceptionOrNull()?.message ?: "备份失败"
                ErrorLog.add("备份", backupError.orEmpty(), result.exceptionOrNull())
                backupSteps.update { RusticService.markFailed(it) }
            } else {
                reloadKey++
                // 备份完只刷新备份数，不用重新拉整个应用列表
                viewModel.refreshBackupInfo()
            }
        }
    }

    /** 开始备份：先关选项弹窗，再打开不可关闭的进度弹窗。 */
    fun startBackup(
        kinds: Set<String> = setOfNotNull(
            "apk".takeIf { backupApk },
            "user".takeIf { backupUser },
            "data".takeIf { backupData }
        )
    ) {
        runBackup(kinds)
    }

    /** 覆盖备份：沿用旧快照的 label / tag / 备注，成功后删掉旧快照。 */
    fun overwriteSelected(entry: BackupEntry) {
        val kinds = managingKinds
        if (entry.selectedSnapshots(kinds).isEmpty()) {
            Toast.makeText(context, "请先勾选要覆盖的内容", Toast.LENGTH_SHORT).show()
            return
        }
        runBackup(kinds, overwrite = entry)
    }

    /** 删除勾选的快照。 */
    fun deleteSelected(entry: BackupEntry) {
        val kinds = managingKinds
        if (entry.selectedSnapshots(kinds).isEmpty()) {
            Toast.makeText(context, "请先勾选要删除的内容", Toast.LENGTH_SHORT).show()
            return
        }

        managingEntry = null
        scope.launch {
            KeepAwake.acquire(context)
            try {
                RusticService.deleteSelected(entry, kinds)
                    .onSuccess {
                        reloadKey++
                        // 删掉一条备份：只刷新列表里的备份数 / 备份时间
                        viewModel.refreshBackupInfo()
                    }
                    .onFailure {
                        ErrorLog.add("删除", "删除失败", it)
                        Toast.makeText(
                            context,
                            "删除失败：${it.message ?: "未知错误"}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
            } finally {
                KeepAwake.release()
            }
        }
    }

    /** 删除当前包下的所有快照（含图标快照）。 */
    fun deleteAllBackups() {
        confirmDeleteAll = false
        scope.launch {
            KeepAwake.acquire(context)
            try {
                RusticService.deleteAll(displayApp.packageName)
                    .onSuccess { count ->
                        Toast.makeText(
                            context,
                            if (count == 0) "没有可删除的备份" else "删除成功",
                            Toast.LENGTH_SHORT
                        ).show()
                        reloadKey++
                        // 备份全删了，只在备份里存在的应用也要从列表里去掉，整表重读
                        viewModel.loadApps(scrollToTop = false)
                    }
                    .onFailure {
                        ErrorLog.add("删除", "删除失败", it)
                        Toast.makeText(
                            context,
                            "删除失败：${it.message ?: "未知错误"}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
            } finally {
                KeepAwake.release()
            }
        }
    }

    /** 恢复勾选的快照。 */
    fun restoreSelected(entry: BackupEntry) {
        val kinds = managingKinds
        RusticService.restoreBlockedReason(entry, kinds, isInstalled)?.let { reason ->
            Toast.makeText(context, reason, Toast.LENGTH_SHORT).show()
            return
        }

        managingEntry = null
        backupSteps.value = RusticService.restoreSteps(entry, kinds)
        val steps = backupSteps.value

        progressTitle = "正在恢复"
        backupError = null
        backupRunning = true
        showBackupProgress = true

        BackupNotifier.start(
            context = context,
            action = "恢复",
            appName = displayApp.name,
            packageName = displayApp.packageName,
            userId = displayApp.userId,
            appIndex = 1,
            appTotal = 1
        )

        scope.launch {
            val result = RusticService.restore(
                app = displayApp,
                entry = entry,
                kinds = kinds,
                installed = isInstalled
            ) { progress ->
                backupSteps.update { RusticService.applyProgress(it, progress) }

                val stepIndex = steps.indexOfFirst { it.key == progress.stage }
                BackupNotifier.update(
                    context = context,
                    action = "恢复",
                    appName = displayApp.name,
                    packageName = displayApp.packageName,
                    userId = displayApp.userId,
                    appIndex = 1,
                    appTotal = 1,
                    stageLabel = BackupNotifier.stageLabel(progress.stage),
                    stageIndex = if (stepIndex >= 0) stepIndex + 1 else 1,
                    stageTotal = steps.size
                )
            }

            backupRunning = false
            BackupNotifier.finish(context, "恢复", if (result.isFailure) 1 else 0, 1)
            if (result.isFailure) {
                backupError = result.exceptionOrNull()?.message ?: "恢复失败"
                ErrorLog.add("恢复", backupError.orEmpty(), result.exceptionOrNull())
                backupSteps.update { RusticService.markFailed(it) }
            } else {
                reloadKey++
                // 勾了 apk 就一定装上了：本地先翻成已安装（名称/版本/包名本来就在备份快照里），
                // 列表刷新回来后自然以列表数据为准。
                if (kinds.contains("apk")) restoredInstalled = true
                // 恢复可能把它装上了：清掉图标的负缓存，否则图标一直是首字母占位块
                AppIconStore.invalidate(displayApp.packageName, displayApp.userId)
                // 恢复可能装上/改动了应用，列表要重新读一次
                viewModel.loadApps(scrollToTop = false)
            }
        }
    }

    // 进入页面 / 备份结束后，按包名 tag 查一次仓库
    LaunchedEffect(displayApp.packageName, backupRunning, reloadKey) {
        if (backupRunning) return@LaunchedEffect
        entries = withContext(Dispatchers.IO) {
            RusticService.loadEntries(displayApp.packageName, displayApp.name)
                .getOrNull()
                .orEmpty()
        }
    }

    /** 修改同一行所有快照的备注；description 为 null 表示移除。 */
    fun applyDescription(entry: BackupEntry, description: String?) {
        editingEntry = null

        // 先在页面上生效，再异步同步到仓库
        val targetIds = entry.ids.toSet()
        entries = entries.map {
            if (it.ids.toSet() == targetIds) it.copy(description = description) else it
        }

        scope.launch {
            RusticService.updateEntryDescription(entry, description)
                .onSuccess {
                    reloadKey++
                }.onFailure {
                    ErrorLog.add("备注", "修改备注失败", it)
                    Toast.makeText(
                        context,
                        "修改备注失败：${it.message ?: "未知错误"}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
        }
    }

    fun perform(action: AppAction) {
        showOptions = false
        when (action) {
            AppAction.LAUNCH -> launchApp(context, displayApp)
            AppAction.APP_SETTINGS -> openAppSettings(context, displayApp)
            // 破坏性操作先确认
            AppAction.CLEAR_DATA, AppAction.UNINSTALL -> confirmAction = action
            else -> runAction(action)
        }
    }

    Scaffold(
        containerColor = pageBackground,
        topBar = {
            AppTopBar(
                viewModel = viewModel,
                title = "应用信息",
                pageBackground = pageBackground,
                searchBarBackground = searchBarBackground,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                // 中间按钮换成帮助图标（无背景 IconButton）
                middleIcon = Icons.AutoMirrored.Filled.HelpOutline,
                onMiddleClick = { showHelpDialog = true },
                showSortMenu = false,
                searchPlaceholder = "搜索备注",
                searchOpen = detailSearchOpen,
                searchQueryText = detailQuery,
                onToggleSearch = {
                    detailSearchOpen = !detailSearchOpen
                    if (!detailSearchOpen) detailQuery = ""
                },
                onSearchQueryChange = { detailQuery = it },
                moreMenuContent = { onDismiss ->
                    DropdownMenuItem(
                        text = { Text("删除全部备份") },
                        onClick = {
                            onDismiss()
                            confirmDeleteAll = true
                        }
                    )
                }
            )
        }
    ) { paddingValues ->
        AppListContainer(
            items = visibleEntries,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            header = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 图标 + 文字区域：点击弹出应用操作菜单
                    Box(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable(enabled = canInteract) { showOptions = true }
                                .alpha(if (isUninstalled) 0.45f else 1f)
                                .padding(horizontal = 6.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(modifier = Modifier.size(48.dp)) {
                                // 应用没装（只在备份里）时用仓库里存的图标，和列表保持一致
                                val appIcon = rememberAppIcon(
                                    displayApp.packageName,
                                    displayApp.userId
                                )
                                AppIcon(
                                    icon = appIcon ?: rememberBackupIcon(displayApp.packageName),
                                    name = displayApp.name,
                                    modifier = Modifier.fillMaxSize(),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                if (isNonPrimaryUser(displayApp.userId)) {
                                    UserBadge(
                                        userId = displayApp.userId,
                                        compact = true,
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .padding(2.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (isFrozen) {
                                        FrozenSnowflake()
                                        Spacer(modifier = Modifier.width(4.dp))
                                    }
                                    Text(
                                        text = displayApp.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        textDecoration = if (isUninstalled) {
                                            TextDecoration.LineThrough
                                        } else {
                                            null
                                        }
                                    )
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = displayApp.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                // 应用没装时用最新备份里记录的版本
                                val headerVersion = displayApp.version
                                    .ifBlank { entries.firstOrNull()?.version.orEmpty() }
                                if (headerVersion.isNotBlank()) {
                                    Text(
                                        text = headerVersion,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        AppOptionsMenu(
                            expanded = showOptions,
                            frozen = isFrozen,
                            onDismiss = { showOptions = false },
                            onAction = { perform(it) }
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    if (isBusy) {
                        Box(
                            modifier = Modifier.size(44.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    } else {
                        FilledTonalButton(
                            onClick = { showBackupOptions = true },
                            enabled = canInteract,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                        ) {
                            Text("备份")
                        }
                    }
                }
            }
        ) { index, entry, shape ->
            AppItemCard(
                icon = null,
                title = entry.title,
                subtitle = listOfNotNull(entry.userName, formatDateTime(entry.time))
                    .joinToString(" · "),
                bottomText = entry.description ?: "无备注",
                onClick = {
                    val select = onSelectBackup
                    if (select != null) {
                        select(entry)
                    } else {
                        noteInput = entry.description.orEmpty()
                        editingEntry = entry
                    }
                },
                shape = shape,
                titleStyle = MaterialTheme.typography.labelLarge,
                titleFontWeight = FontWeight.Normal,
                // 只有最新一条（第一行）用强调色，其余走默认颜色
                subtitleColor = if (index == 0) {
                    subtitleColor
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                bottomTextColor = bottomTextColor,
                // 右侧不留卡片内边距，交给下面的可点区域撑到卡片边缘
                endPadding = 0.dp,
                trailingContent = {
                    // 按钮四周再留一圈「也算点了管理」的区域：
                    val manageInteraction = remember { MutableInteractionSource() }
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .clickable(
                                interactionSource = manageInteraction,
                                indication = null
                            ) {
                                managingKinds = entry.availableKinds.toSet()
                                managingEntry = entry
                            }
                            .padding(start = 8.dp, end = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        FilledTonalButton(
                            onClick = {
                                managingKinds = entry.availableKinds.toSet()
                                managingEntry = entry
                            },
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                        ) {
                            Text("管理")
                        }
                    }
                }
            )
        }
    }

    // 破坏性操作二次确认
    confirmAction?.let { action ->
        val uninstall = action == AppAction.UNINSTALL
        AlertDialog(
            onDismissRequest = { confirmAction = null },
            title = { Text(if (uninstall) "卸载应用" else "清空数据") },
            text = {
                Text(
                    if (uninstall) {
                        "确定要卸载「${displayApp.name}」吗？此操作不可撤销。"
                    } else {
                        "确定要清空「${displayApp.name}」的全部数据吗？此操作不可撤销。"
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmAction = null
                        runAction(action)
                    }
                ) {
                    Text("确定", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmAction = null }) {
                    Text("取消")
                }
            }
        )
    }

    // 删除全部备份二次确认
    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("删除全部备份") },
            text = {
                Text("确定要删除「${displayApp.name}」的全部备份吗？此操作不可撤销。")
            },
            confirmButton = {
                TextButton(onClick = { deleteAllBackups() }) {
                    Text("确定", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteAll = false }) {
                    Text("取消")
                }
            }
        )
    }

    // 帮助对话框
    if (showHelpDialog) {
        BackupHelpDialog(onDismiss = { showHelpDialog = false })
    }

    // 管理弹窗：用自定义 Dialog，按钮直接放在内容里，点一下就响应
    managingEntry?.let { entry ->
        Dialog(onDismissRequest = {
            managingEntry = null
            doubleClickGuard.reset()
        }) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    BackupEntry.KINDS.forEach { kind ->
                        val snaps = entry.snapshotsOf(kind)
                        val exists = snaps.isNotEmpty()
                        ManageOptionRow(
                            label = kind,
                            checked = kind in managingKinds,
                            enabled = exists,
                            size = if (exists) formatSize(snaps.sumOf { it.size }) else "-",
                            id = if (exists) {
                                snaps.joinToString(" ") { it.id.take(8) }
                            } else {
                                ""
                            },
                            onCheckedChange = { checked ->
                                managingKinds = if (checked) {
                                    managingKinds + kind
                                } else {
                                    managingKinds - kind
                                }
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        // 应用没装就没有可覆盖的内容，按钮不显示
                        if (RusticService.canOverwrite(isInstalled)) {
                            TextButton(
                                onClick = {
                                    if (doubleClickGuard.consume("overwrite")) {
                                        overwriteSelected(entry)
                                    }
                                }
                            ) {
                                Text("覆盖备份")
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            onClick = {
                                if (doubleClickGuard.consume("delete")) {
                                    deleteSelected(entry)
                                }
                            }
                        ) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                        TextButton(onClick = { restoreSelected(entry) }) {
                            Text("恢复")
                        }
                    }
                }
            }
        }
    }

    // 备注编辑：用自定义 Dialog，好把「移除备注」放到最左边
    editingEntry?.let { entry ->
        Dialog(onDismissRequest = { editingEntry = null }) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "备注",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = noteInput,
                        onValueChange = { noteInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("备注内容") },
                        maxLines = 4
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 移除备注放最左边，其余靠右
                        TextButton(onClick = { applyDescription(entry, null) }) {
                            Text("移除备注", color = MaterialTheme.colorScheme.error)
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        TextButton(onClick = { editingEntry = null }) {
                            Text("取消")
                        }
                        TextButton(
                            onClick = {
                                applyDescription(entry, noteInput.trim().ifBlank { null })
                            }
                        ) {
                            Text("确定")
                        }
                    }
                }
            }
        }
    }

    // 备份选项
    if (showBackupOptions) {
        AlertDialog(
            onDismissRequest = { showBackupOptions = false },
            title = { Text(text = "备份", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column {
                    BackupOptionRow("apk(含 obb)", backupApk) { backupApk = it }
                    BackupOptionRow("user", backupUser) { backupUser = it }
                    BackupOptionRow("data", backupData) { backupData = it }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { startBackup() },
                    enabled = backupApk || backupUser || backupData
                ) {
                    Text("备份")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBackupOptions = false }) {
                    Text("取消")
                }
            }
        )
    }

    // 备份进度：运行过程中不可关闭
    if (showBackupProgress) {
        BackupProgressDialog(
            title = progressTitle,
            doneTitle = if (progressTitle == "正在备份") "备份成功" else "恢复成功",
            subtitle = "开始:${displayApp.packageName}",
            sections = listOf(ProgressSection(title = "", steps = currentSteps)),
            isRestore = progressTitle != "正在备份",
            error = backupError,
            running = backupRunning,
            onDismiss = { showBackupProgress = false }
        )
    }
}


/** 管理弹窗里的选项行：勾选框 + 名称 + 大小和快照 id。 */
@Composable
private fun ManageOptionRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    size: String,
    id: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val accent = MaterialTheme.colorScheme.primary
        val active = checked && enabled
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (active) accent else Color.Transparent)
                .border(
                    width = 1.5.dp,
                    color = when {
                        active -> accent
                        enabled -> MaterialTheme.colorScheme.outline
                        else -> MaterialTheme.colorScheme.outlineVariant
                    },
                    shape = RoundedCornerShape(4.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (active) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(13.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.outline
            }
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = size,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = id,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

/** 紧凑的复选行，四个选项之间靠得比较近。 */
@Composable
private fun BackupOptionRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val accent = MaterialTheme.colorScheme.primary
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (checked) accent else Color.Transparent)
                .border(
                    width = 1.5.dp,
                    color = if (checked) accent else MaterialTheme.colorScheme.outline,
                    shape = RoundedCornerShape(4.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (checked) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(13.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}

// ==================== 应用操作实现 ====================

private fun launchApp(context: Context, app: AppInfo) {
    if (app.userId == 0) {
        val intent = runCatching {
            context.packageManager.getLaunchIntentForPackage(app.packageName)
        }.getOrNull()

        if (intent == null) {
            Toast.makeText(context, "该应用没有可启动的界面", Toast.LENGTH_SHORT).show()
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure {
                Toast.makeText(context, "启动失败：${it.message ?: "未知错误"}", Toast.LENGTH_SHORT)
                    .show()
            }
        return
    }

    // 非主用户：用 LauncherApps 指定用户启动
    runCatching {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        val user = UserHandle.getUserHandleForUid(app.userId * 100_000)
        val info = launcherApps?.getActivityList(app.packageName, user)?.firstOrNull()
        if (info == null) {
            Toast.makeText(context, "该应用没有可启动的界面", Toast.LENGTH_SHORT).show()
        } else {
            launcherApps.startMainActivity(info.componentName, info.user, null, null)
        }
    }.onFailure {
        Toast.makeText(context, "启动失败：${it.message ?: "未知错误"}", Toast.LENGTH_SHORT).show()
    }
}

private fun openAppSettings(context: Context, app: AppInfo) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", app.packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (app.userId != 0) {
            putExtra("android.intent.extra.user_handle", app.userId)
        }
    }
    runCatching { context.startActivity(intent) }
        .onFailure {
            Toast.makeText(context, "打开系统设置失败", Toast.LENGTH_SHORT).show()
        }
}

