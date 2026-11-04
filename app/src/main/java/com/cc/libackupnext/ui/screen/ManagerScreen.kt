package com.cc.libackupnext.ui.screen

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.net.Uri
import android.os.UserHandle
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.DocumentsContract
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cc.libackupnext.data.BackupEntry
import com.cc.libackupnext.data.BackupGroup
import com.cc.libackupnext.data.BackupStep
import com.cc.libackupnext.data.BackupTarget
import com.cc.libackupnext.entity.AppInfo
import com.cc.libackupnext.entity.isVersionChangedFrom
import com.cc.libackupnext.service.AppManagerService
import com.cc.libackupnext.service.BackupProgress
import com.cc.libackupnext.service.RusticClient
import com.cc.libackupnext.ui.AppTopBar
import com.cc.libackupnext.ui.MainViewModel
import com.cc.libackupnext.ui.dialog.BackupHelpDialog
import com.cc.libackupnext.ui.dialog.BackupProgressDialog
import com.cc.libackupnext.ui.dialog.ItemAction
import com.cc.libackupnext.ui.dialog.ProgressSection
import com.cc.libackupnext.ui.dialog.rememberDoubleClickGuard
import com.cc.libackupnext.ui.screen.detail.BackupEntryRow
import com.cc.libackupnext.ui.screen.detail.BackupManageDialog
import com.cc.libackupnext.ui.screen.detail.BackupOptionsDialog
import com.cc.libackupnext.ui.screen.detail.ConfirmActionDialog
import com.cc.libackupnext.ui.screen.detail.ConfirmDeleteAllDialog
import com.cc.libackupnext.ui.screen.detail.DetailHeader
import com.cc.libackupnext.ui.screen.detail.NoteEditDialog
import com.cc.libackupnext.ui.screen.detail.detailTargetOf
import com.cc.libackupnext.ui.theme.LocalAppColors
import com.cc.libackupnext.ui.util.AppIconStore
import com.cc.libackupnext.ui.util.AppListContainer
import com.cc.libackupnext.ui.util.countText
import com.cc.libackupnext.ui.util.systemCount
import com.cc.libackupnext.util.BackupNotifier
import com.cc.libackupnext.util.ErrorLog
import com.cc.libackupnext.util.KeepAwake
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    onSelectBackup: ((BackupEntry) -> Unit)? = null,
    /** 备份目标类型：应用 / 目录……，标题、备份选项、安装状态都按它来。 */
    target: BackupTarget = BackupTarget.APP,
    /** 顶栏标题，默认取 target 的标题。 */
    title: String = target.detailTitle,
    /** 传了才在右上角菜单里出现「移除该文件夹」。 */
    onRemoveDir: (() -> Unit)? = null
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
    // 系统数据目标（Wi-Fi / 联系人 / 通话记录）的副标题要显示条数
    val systemCounts by viewModel.systemDataCounts.collectAsState()
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

    // 回到前台时重算目录还在不在 —— 用户可能刚在别的应用里把它删了或重建了
    val dirLifecycleOwner = LocalLifecycleOwner.current
    var dirExistsTick by remember { mutableStateOf(0) }
    DisposableEffect(dirLifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) dirExistsTick++
        }
        dirLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { dirLifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val dirExists = remember(dirExistsTick, displayApp.packageName) {
        java.io.File(displayApp.packageName).isDirectory
    }

    // 目录的「已不存在」直接看磁盘；应用还是看安装状态
    val isUninstalled = when (target) {
        BackupTarget.DIR -> !dirExists
        BackupTarget.APP -> !restoredInstalled &&
                (forceUninstalled ||
                        displayApp.onlyInBackup ||
                        (loadedUserId == currentUserId && appForCurrentUser == null))
        // 系统数据没有「装没装」这回事
        BackupTarget.WIFI, BackupTarget.CONTACTS,
        BackupTarget.CALLS -> false
    }

    var isBusy by remember { mutableStateOf(false) }
    var confirmAction by remember { mutableStateOf<ItemAction?>(null) }

    // ==================== 备份 ====================
    var showBackupOptions by remember { mutableStateOf(false) }
    // 备份弹窗里勾了哪些内容：默认全勾
    var backupGroups by remember { mutableStateOf(BackupTarget.APP.kinds.toSet()) }
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
    var managingKinds by remember { mutableStateOf<Set<BackupGroup>>(emptySet()) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    // 移除文件夹前的确认（会连它的备份一起删）
    var confirmRemoveDir by remember { mutableStateOf(false) }

    // 覆盖备份 / 删除需要双击确认
    val doubleClickGuard = rememberDoubleClickGuard()
    var noteInput by remember { mutableStateOf("") }
    val backupSteps = remember { MutableStateFlow<List<BackupStep>>(emptyList()) }
    val currentSteps by backupSteps.collectAsState()

    val isInstalled = !isUninstalled
    // 执行 root 侧操作，完成后刷新应用状态
    fun runAction(action: ItemAction) {
        if (isBusy) return
        scope.launch {
            isBusy = true
            try {
                withContext(Dispatchers.IO) {
                    when (action) {
                        ItemAction.FORCE_STOP ->
                            AppManagerService.forceStop(displayApp.packageName, displayApp.userId)

                        ItemAction.FREEZE ->
                            AppManagerService.freeze(displayApp.packageName, displayApp.userId)

                        ItemAction.UNFREEZE ->
                            AppManagerService.unFreeze(displayApp.packageName, displayApp.userId)

                        ItemAction.CLEAR_DATA ->
                            AppManagerService.clearAppData(displayApp.packageName, displayApp.userId)

                        ItemAction.UNINSTALL ->
                            AppManagerService.unInstall(displayApp.packageName, displayApp.userId)

                        ItemAction.DELETE_DIR -> deleteDirectory(displayApp.packageName)

                        ItemAction.WIFI_ON, ItemAction.WIFI_OFF ->
                            RusticClient.setWifiEnabled(action == ItemAction.WIFI_ON).getOrThrow()

                        // 这几个只做页面跳转，不碰 root
                        ItemAction.LAUNCH,
                        ItemAction.APP_SETTINGS,
                        ItemAction.OPEN_DIR,
                        ItemAction.OPEN_WIFI_SETTINGS,
                        ItemAction.OPEN_CONTACTS,
                        ItemAction.OPEN_CALL_LOG -> Unit
                    }
                }

                // 冻结 / 解冻 / 卸载后刷新应用状态（重拉列表，状态本来就在列表数据里）
                when (action) {
                    ItemAction.FREEZE -> {
                        isFrozen = true
                        viewModel.loadList(scrollToTop = false)
                    }

                    ItemAction.UNFREEZE -> {
                        isFrozen = false
                        viewModel.loadList(scrollToTop = false)
                    }

                    ItemAction.UNINSTALL -> {
                        forceUninstalled = true
                        restoredInstalled = false
                        viewModel.loadList(scrollToTop = false)
                    }

                    ItemAction.DELETE_DIR -> dirExistsTick++

                    // 这几个动作不改页面上的状态
                    ItemAction.LAUNCH,
                    ItemAction.FORCE_STOP,
                    ItemAction.APP_SETTINGS,
                    ItemAction.CLEAR_DATA,
                    ItemAction.OPEN_DIR,
                    ItemAction.OPEN_WIFI_SETTINGS,
                    ItemAction.OPEN_CONTACTS,
                    ItemAction.OPEN_CALL_LOG,
                    ItemAction.WIFI_ON,
                    ItemAction.WIFI_OFF -> Unit
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
    fun runBackup(groups: Set<BackupGroup>, overwrite: BackupEntry? = null) {
        showBackupOptions = false
        managingEntry = null
        backupError = null
        progressTitle = "正在备份"
        backupRunning = true
        showBackupProgress = true
        backupSteps.value = RusticClient.backupSteps(groups)
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
                backupSteps.update { RusticClient.applyProgress(it, progress) }

                val stepIndex = steps.indexOfFirst { it.kind.key == progress.stage }
                BackupNotifier.update(
                    context = context,
                    action = "备份",
                    appName = displayApp.name,
                    packageName = displayApp.packageName,
                    userId = displayApp.userId,
                    appIndex = 1,
                    appTotal = 1,
                    stageLabel = progress.stage,
                    stageIndex = if (stepIndex >= 0) stepIndex + 1 else 1,
                    stageTotal = steps.size
                )
            }

            val result = if (overwrite == null) {
                RusticClient.backup(displayApp, groups, onProgress)
            } else {
                RusticClient.overwrite(displayApp, overwrite, groups, onProgress)
            }

            backupRunning = false
            BackupNotifier.finish(context, "备份", if (result.isFailure) 1 else 0, 1)
            if (result.isFailure) {
                backupError = result.exceptionOrNull()?.message ?: "备份失败"
                ErrorLog.add("备份", backupError.orEmpty(), result.exceptionOrNull())
                backupSteps.update { RusticClient.markFailed(it) }
            } else {
                reloadKey++
                // 备份完只刷新备份数，不用重新拉整个应用列表
                viewModel.refreshBackupInfo()
            }
        }
    }

    /** 开始备份：先关选项弹窗，再打开不可关闭的进度弹窗。 */
    fun startBackup(
        groups: Set<BackupGroup> = target.backupKinds(backupGroups)
    ) {
        runBackup(groups)
    }

    /** 覆盖备份：沿用旧快照的 label / tag / 备注，成功后删掉旧快照。 */
    fun overwriteSelected(entry: BackupEntry) {
        val groups = managingKinds
        if (entry.selectedSnapshots(groups).isEmpty()) {
            Toast.makeText(context, "请先勾选要覆盖的内容", Toast.LENGTH_SHORT).show()
            return
        }
        runBackup(groups, overwrite = entry)
    }

    /** 删除勾选的快照。 */
    fun deleteSelected(entry: BackupEntry) {
        val groups = managingKinds
        if (entry.selectedSnapshots(groups).isEmpty()) {
            Toast.makeText(context, "请先勾选要删除的内容", Toast.LENGTH_SHORT).show()
            return
        }

        managingEntry = null
        scope.launch {
            KeepAwake.acquire(context)
            try {
                RusticClient.deleteSelected(entry, groups)
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
                RusticClient.deleteAll(displayApp.packageName)
                    .onSuccess { count ->
                        Toast.makeText(
                            context,
                            if (count == 0) "没有可删除的备份" else "删除成功",
                            Toast.LENGTH_SHORT
                        ).show()
                        reloadKey++
                        // 备份全删了，只在备份里存在的应用也要从列表里去掉，整表重读
                        viewModel.loadList(scrollToTop = false)
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
        val groups = managingKinds
        RusticClient.restoreBlockedReason(entry, groups, isInstalled)?.let { reason ->
            Toast.makeText(context, reason, Toast.LENGTH_SHORT).show()
            return
        }

        managingEntry = null
        backupSteps.value = RusticClient.restoreSteps(entry, groups)
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
            val result = RusticClient.restore(
                app = displayApp,
                entry = entry,
                groups = groups,
                installed = isInstalled
            ) { progress ->
                backupSteps.update { RusticClient.applyProgress(it, progress) }

                val stepIndex = steps.indexOfFirst { it.kind.key == progress.stage }
                BackupNotifier.update(
                    context = context,
                    action = "恢复",
                    appName = displayApp.name,
                    packageName = displayApp.packageName,
                    userId = displayApp.userId,
                    appIndex = 1,
                    appTotal = 1,
                    stageLabel = progress.stage,
                    stageIndex = if (stepIndex >= 0) stepIndex + 1 else 1,
                    stageTotal = steps.size
                )
            }

            backupRunning = false
            BackupNotifier.finish(context, "恢复", if (result.isFailure) 1 else 0, 1)
            if (result.isFailure) {
                backupError = result.exceptionOrNull()?.message ?: "恢复失败"
                ErrorLog.add("恢复", backupError.orEmpty(), result.exceptionOrNull())
                backupSteps.update { RusticClient.markFailed(it) }
            } else {
                reloadKey++
                // 恢复可能把已经删掉的目录重新建出来，重算一次「还在不在」，
                // 否则 header 会一直停在划线置灰的状态
                dirExistsTick++
                // 勾了 apk 就一定装上了：本地先翻成已安装（名称/版本/包名本来就在备份快照里），
                // 列表刷新回来后自然以列表数据为准。
                if (BackupGroup.APK in groups) restoredInstalled = true
                // 恢复可能把它装上了：清掉图标的负缓存，否则图标一直是首字母占位块
                AppIconStore.invalidate(displayApp.packageName, displayApp.userId)
                // 恢复可能装上/改动了应用，列表要重新读一次
                viewModel.loadList(scrollToTop = false)
            }
        }
    }

    // 进入页面 / 备份结束后，按包名 tag 查一次仓库
    LaunchedEffect(displayApp.packageName, backupRunning, reloadKey) {
        if (backupRunning) return@LaunchedEffect
        entries = withContext(Dispatchers.IO) {
            RusticClient.loadEntries(displayApp.packageName, displayApp.name)
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
            RusticClient.updateEntryDescription(entry, description)
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

    fun perform(action: ItemAction) {
        when (action) {
            ItemAction.LAUNCH -> launchApp(context, displayApp)
            ItemAction.APP_SETTINGS -> openAppSettings(context, displayApp)
            ItemAction.OPEN_DIR -> openDirectory(context, displayApp.packageName)
            ItemAction.OPEN_WIFI_SETTINGS -> openWifiSettings(context)
            ItemAction.OPEN_CONTACTS -> openContacts(context)
            ItemAction.OPEN_CALL_LOG -> openCallLog(context)
            // 破坏性操作先确认
            ItemAction.CLEAR_DATA, ItemAction.UNINSTALL, ItemAction.DELETE_DIR -> confirmAction =
                action
            // 剩下的是直接执行的 root 操作
            ItemAction.FORCE_STOP, ItemAction.FREEZE, ItemAction.UNFREEZE,
            ItemAction.WIFI_ON, ItemAction.WIFI_OFF -> runAction(action)
        }
    }

    Scaffold(
        containerColor = pageBackground,
        topBar = {
            AppTopBar(
                viewModel = viewModel,
                title = title,
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
                // 详情页不排序，但帮助按钮要留着
                showMiddleButton = true,
                // 用户切换只跟应用有关，文件详情页不显示
                showUserButton = target.isApp,
                searchPlaceholder = "搜索备注",
                searchOpen = detailSearchOpen,
                searchQueryText = detailQuery,
                onToggleSearch = {
                    detailSearchOpen = !detailSearchOpen
                    if (!detailSearchOpen) detailQuery = ""
                },
                onSearchQueryChange = { detailQuery = it },
                moreMenuContent = { onDismiss ->
                    if (onRemoveDir != null) {
                        DropdownMenuItem(
                            text = { Text("移除该文件夹") },
                            onClick = {
                                onDismiss()
                                // 有备份就得先确认：移除会把它们一起删掉
                                if (entries.isNotEmpty()) {
                                    confirmRemoveDir = true
                                } else {
                                    onRemoveDir?.invoke()
                                }
                            }
                        )
                    }
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
                DetailHeader(
                    target = detailTargetOf(
                        kind = target,
                        app = displayApp.copy(isFrozen = isFrozen),
                        isUninstalled = isUninstalled,
                        latestVersion = entries.firstOrNull()?.version,
                        onAction = { perform(it) },
                        onBackup = { showBackupOptions = true },
                        subtitle = if (target.isFixed) {
                            target.countText(target.systemCount(systemCounts))
                        } else {
                            null
                        }
                    ),
                    isBusy = isBusy
                )
            }
        ) { index, entry, shape ->
            BackupEntryRow(
                entry = entry,
                isLatest = index == 0,
                shape = shape,
                latestSubtitleColor = subtitleColor,
                bottomTextColor = bottomTextColor,
                onClick = {
                    val select = onSelectBackup
                    if (select != null) {
                        select(entry)
                    } else {
                        noteInput = entry.description.orEmpty()
                        editingEntry = entry
                    }
                },
                onManage = {
                    managingKinds = entry.availableKinds.toSet()
                    managingEntry = entry
                }
            )
        }
    }

    confirmAction?.let { action ->
        val (title, message) = confirmDialogText(action, displayApp.name, displayApp.packageName)
        ConfirmActionDialog(
            title = title,
            message = message,
            onDismiss = { confirmAction = null },
            onConfirm = {
                confirmAction = null
                runAction(action)
            }
        )
    }

    if (confirmDeleteAll) {
        ConfirmDeleteAllDialog(
            appName = displayApp.name,
            onDismiss = { confirmDeleteAll = false },
            onConfirm = { deleteAllBackups() }
        )
    }

    // 移除文件夹确认：有备份时说明会一起删掉
    if (confirmRemoveDir) {
        AlertDialog(
            onDismissRequest = { confirmRemoveDir = false },
            title = { Text("移除该文件夹") },
            text = {
                Text(
                    "「${displayApp.packageName}」有 ${entries.size} 个备份，" +
                            "移除后这些备份会被一起删除，此操作不可撤销。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRemoveDir = false
                        deleteAllBackups()
                        onRemoveDir?.invoke()
                    }
                ) {
                    Text("移除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemoveDir = false }) {
                    Text("取消")
                }
            }
        )
    }

    // 帮助对话框：内容随目标类型变（应用 / 文件 / Wi-Fi / 联系人 / 通话记录）
    if (showHelpDialog) {
        BackupHelpDialog(
            lines = target.helpLines,
            onDismiss = { showHelpDialog = false }
        )
    }

    managingEntry?.let { entry ->
        BackupManageDialog(
            entry = entry,
            target = target,
            isVersionChanged = displayApp.isVersionChangedFrom(entry.version, isInstalled),
            canOverwrite = RusticClient.canOverwrite(isInstalled),
            checkedKinds = managingKinds,
            onCheckedChange = { kind, checked ->
                managingKinds = if (checked) managingKinds + kind else managingKinds - kind
            },
            onDismiss = {
                managingEntry = null
                doubleClickGuard.reset()
            },
            onOverwrite = {
                if (doubleClickGuard.consume("overwrite")) overwriteSelected(entry)
            },
            onDelete = {
                if (doubleClickGuard.consume("delete")) deleteSelected(entry)
            },
            onRestore = { restoreSelected(entry) }
        )
    }

    editingEntry?.let { entry ->
        NoteEditDialog(
            note = noteInput,
            onNoteChange = { noteInput = it },
            onDismiss = { editingEntry = null },
            onRemove = { applyDescription(entry, null) },
            onConfirm = { applyDescription(entry, noteInput.trim().ifBlank { null }) }
        )
    }

    if (showBackupOptions) {
        BackupOptionsDialog(
            target = target,
            dirPath = displayApp.packageName,
            checked = backupGroups,
            onToggle = { group, checked ->
                backupGroups = if (checked) backupGroups + group else backupGroups - group
            },
            onDismiss = { showBackupOptions = false },
            onConfirm = { startBackup() }
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

// ==================== 应用操作实现 ====================

/**
 * 按操作类型生成确认弹窗的标题和正文。
 */
private fun confirmDialogText(
    action: ItemAction,
    appName: String,
    appPath: String
): Pair<String, String> =
    when (action) {
        ItemAction.DELETE_DIR ->
            "删除文件夹" to "确定要删除「$appPath」吗？此操作不可撤销。"

        ItemAction.UNINSTALL ->
            "卸载应用" to "确定要卸载「$appName」吗？此操作不可撤销。"

        else ->
            "清空数据" to "确定要清空「$appName」的全部数据吗？此操作不可撤销。"
    }

/**
 * 用系统的「文件」应用打开目录。
 */
private fun openDirectory(context: Context, path: String) {
    val docId = when {
        // 内部存储：/storage/emulated/0/DCIM → primary:DCIM
        path.startsWith("/storage/emulated/0/") -> {
            val inner = path.removePrefix("/storage/emulated/0/")
            if (inner.isNotEmpty()) "primary:$inner" else null
        }

        // 外置 SD 卡：/storage/1A2B-3C4D/Music → 1A2B-3C4D:Music
        path.startsWith("/storage/") -> {
            val rest = path.removePrefix("/storage/")
            val volume = rest.substringBefore('/')
            val inner = rest.substringAfter('/', "")
            if (inner.isEmpty()) null else "$volume:$inner"
        }

        else -> null
    }

    if (docId == null) {
        Toast.makeText(context, "这个位置打不开", Toast.LENGTH_SHORT).show()
        return
    }

    // 先试 tree URI（文件管理器「打开目录」的标准形式，兼容性最好），
    // 再退回 document URI —— 不同 ROM 的文件管理器认的形式不一样。
    val candidates = listOf(
        DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", docId),
        DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", docId)
    )

    for (uri in candidates) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(context.packageManager) == null) continue
        if (runCatching { context.startActivity(intent) }.isSuccess) return
    }

    Toast.makeText(context, "没有能打开文件夹的应用", Toast.LENGTH_SHORT).show()
}

/** 删除整个目录（root）。路径里可能有空格，用单引号包起来并转义。 */
private fun deleteDirectory(path: String) {
    val quoted = "'" + path.replace("'", "'\\''") + "'"
    val result = Shell.cmd("rm -rf $quoted").exec()
    if (!result.isSuccess) {
        throw IllegalStateException(result.err.joinToString().ifBlank { "删除失败" })
    }
}

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

/** 打开系统的 Wi-Fi 设置页。 */
private fun openWifiSettings(context: Context) {
    val intent = Intent(Settings.ACTION_WIFI_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onFailure { Toast.makeText(context, "打开 Wi-Fi 设置失败", Toast.LENGTH_SHORT).show() }
}

/** 打开联系人应用 */
private fun openContacts(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure {
        Toast.makeText(context, "打开联系人失败", Toast.LENGTH_SHORT).show()
    }
}

/** 打开通话记录（没有专门入口就退回拨号盘）。 */
private fun openCallLog(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, CallLog.Calls.CONTENT_URI)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure {
        Toast.makeText(context, "打开通话记录失败", Toast.LENGTH_SHORT).show()
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

