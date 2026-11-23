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
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cc.libackupnext.data.AppInfo
import com.cc.libackupnext.data.BackupEntry
import com.cc.libackupnext.data.BackupGroup
import com.cc.libackupnext.data.BackupProgress
import com.cc.libackupnext.data.BackupStep
import com.cc.libackupnext.data.BackupTarget
import com.cc.libackupnext.data.BatchAction
import com.cc.libackupnext.data.BatchItem
import com.cc.libackupnext.data.BatchOrigin
import com.cc.libackupnext.data.MediaPath
import com.cc.libackupnext.data.PendingBatch
import com.cc.libackupnext.data.isVersionChangedFrom
import com.cc.libackupnext.data.target.ItemAction
import com.cc.libackupnext.data.target.TargetSpecs
import com.cc.libackupnext.data.target.TargetViewKind
import com.cc.libackupnext.service.gateway.BackendErrors
import com.cc.libackupnext.service.progress.OperationOutcome
import com.cc.libackupnext.service.progress.OperationRunner
import com.cc.libackupnext.ui.AppTopBar
import com.cc.libackupnext.ui.MainViewModel
import com.cc.libackupnext.ui.component.AppDialog
import com.cc.libackupnext.ui.component.AppListContainer
import com.cc.libackupnext.ui.component.countText
import com.cc.libackupnext.ui.di.LocalBackupUseCase
import com.cc.libackupnext.ui.di.LocalDeviceGateway
import com.cc.libackupnext.ui.di.LocalOperationPlanner
import com.cc.libackupnext.ui.di.LocalRepoReader
import com.cc.libackupnext.ui.di.LocalRepoWriter
import com.cc.libackupnext.ui.di.LocalRestoreUseCase
import com.cc.libackupnext.ui.dialog.BackupHelpDialog
import com.cc.libackupnext.ui.dialog.BackupProgressDialog
import com.cc.libackupnext.ui.dialog.ProgressSection
import com.cc.libackupnext.ui.dialog.rememberDoubleClickGuard
import com.cc.libackupnext.ui.screen.detail.BackupEntryRow
import com.cc.libackupnext.ui.screen.detail.BackupManageDialog
import com.cc.libackupnext.ui.screen.detail.BackupOptionsDialog
import com.cc.libackupnext.ui.screen.detail.ConfirmActionDialog
import com.cc.libackupnext.ui.screen.detail.DetailHeader
import com.cc.libackupnext.ui.screen.detail.NoteEditDialog
import com.cc.libackupnext.ui.screen.detail.detailTargetOf
import com.cc.libackupnext.ui.theme.heighLightColor
import com.cc.libackupnext.ui.util.AppIconStore
import com.cc.libackupnext.util.AppLog
import com.cc.libackupnext.util.BackupNotifier
import com.cc.libackupnext.util.KeepAwake
import com.cc.libackupnext.util.NotifierSession
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    title: String = TargetSpecs.of(target).detailTitle,
    /** 传了才在右上角菜单里出现「移除该文件夹」。 */
    onRemoveDir: (() -> Unit)? = null,
    /** 是不是从批量页进来的：记进未完成批次，跳回来时才知道返回该回哪一页。 */
    fromBatch: Boolean = false
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // service 层能力：只依赖接口，不认识具体实现
    val reader = LocalRepoReader.current
    val writer = LocalRepoWriter.current
    val planner = LocalOperationPlanner.current
    val device = LocalDeviceGateway.current
    // 备份 / 恢复走编排层：暂停应用、清理过期、导出自身都在里面，页面不用记这些步骤
    val backupCase = LocalBackupUseCase.current
    val restoreCase = LocalRestoreUseCase.current

    // 系统返回键回到上一页，而不是退出应用
    BackHandler(onBack = onBack)

    val pageBackground = MaterialTheme.colorScheme.surfaceContainer
    val searchBarBackground = MaterialTheme.colorScheme.surfaceContainerHighest

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
    //
    // 文件夹：身份是「用户无关的路径」，换用户 = 换到那个空间里的同一个文件夹，
    // 所以路径也要跟着重算 —— 备份源和恢复落点都靠它。
    val displayApp = if (target == BackupTarget.DIR) {
        val path = MediaPath.forUser(app.packageName, currentUserId)
        // 归属跟着路径走：SD 卡不归属任何用户，别给它挂上当前用户的徽标
        app.copy(
            packageName = path,
            userId = MediaPath.userOf(path) ?: MediaPath.PRIMARY_USER_ID
        )
    } else {
        (appForCurrentUser ?: app).copy(userId = currentUserId)
    }

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
    // 目录还在不在：判据和列表页同一个出口（按当前用户算，别的用户的空间要 root 判）
    val dirExistsMap by viewModel.dirExistsMap.collectAsState()
    LaunchedEffect(displayApp.packageName, dirExistsTick) {
        viewModel.probeDirs(listOf(displayApp.packageName))
    }
    val dirExists = remember(dirExistsTick, displayApp.packageName, dirExistsMap) {
        viewModel.dirExists(displayApp.packageName)
    }

    // 目录的「已不存在」直接看磁盘（恢复成功后 dirExistsTick 会重算）；应用看安装状态；
    // 系统数据没有「装没装」这回事。按视图形态分派而不是逐个枚举：加一种新类型只要选对 viewKind。
    val isUninstalled = when (TargetSpecs.of(target).viewKind) {
        TargetViewKind.DIR -> !dirExists
        TargetViewKind.APP -> !restoredInstalled &&
                (forceUninstalled ||
                        displayApp.onlyInBackup ||
                        (loadedUserId == currentUserId && appForCurrentUser == null))

        TargetViewKind.SYSTEM -> false
    }

    var isBusy by remember { mutableStateOf(false) }
    var confirmAction by remember { mutableStateOf<ItemAction?>(null) }

    // ==================== 备份 ====================
    var showBackupOptions by remember { mutableStateOf(false) }
    // 备份弹窗里勾了哪些内容：默认全勾（换目标时要重置，否则会带上上一个目标的勾选）
    var backupGroups by remember(target) { mutableStateOf(target.kinds.toSet()) }
    var showBackupProgress by remember { mutableStateOf(false) }
    var backupRunning by remember { mutableStateOf(false) }

    // 页面被销毁时别把前台服务和通知留在通知栏
    DisposableEffect(Unit) {
        onDispose { if (backupRunning) BackupNotifier.cancel(context) }
    }
    var progressTitle by remember { mutableStateOf("正在备份") }
    var backupError by remember { mutableStateOf<String?>(null) }
    var connectionInterrupted by remember { mutableStateOf(false) }
    var entries by remember { mutableStateOf<List<BackupEntry>>(emptyList()) }

    // 应用信息页用自己的搜索状态，不影响 applist 的搜索框
    var detailSearchOpen by remember { mutableStateOf(false) }
    var detailQuery by remember { mutableStateOf("") }

    // 列表行统一用这套颜色（在列表外读一次，避免 lazy item 里读到不同的值）
    val subtitleColor = MaterialTheme.colorScheme.heighLightColor
    val bottomTextColor = MaterialTheme.colorScheme.onSurfaceVariant

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
    // 移除文件夹前的确认
    var confirmRemoveDir by remember { mutableStateOf(false) }

    // 覆盖备份 / 删除需要双击确认
    val doubleClickGuard = rememberDoubleClickGuard()
    var noteInput by remember { mutableStateOf("") }
    val backupSteps = remember { MutableStateFlow<List<BackupStep>>(emptyList()) }
    val currentSteps by backupSteps.collectAsState()
    // 一次操作的执行骨架：推进度、断连分类、失败标记。批量页共用同一份。
    val runner = remember(planner) { OperationRunner(planner) }

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
                            device.forceStop(displayApp.packageName, displayApp.userId)

                        ItemAction.FREEZE ->
                            device.freeze(displayApp.packageName, displayApp.userId)

                        ItemAction.UNFREEZE ->
                            device.unfreeze(displayApp.packageName, displayApp.userId)

                        ItemAction.CLEAR_DATA ->
                            device.clearData(displayApp.packageName, displayApp.userId)

                        ItemAction.UNINSTALL ->
                            device.uninstall(displayApp.packageName, displayApp.userId)

                        ItemAction.DELETE_DIR -> deleteDirectory(displayApp.packageName)

                        ItemAction.WIFI_ON, ItemAction.WIFI_OFF ->
                            device.setWifiEnabled(action == ItemAction.WIFI_ON).getOrThrow()

                        // 这几个只做页面跳转，不碰 root
                        ItemAction.LAUNCH,
                        ItemAction.APP_SETTINGS,
                        ItemAction.OPEN_DIR,
                        ItemAction.OPEN_WIFI_SETTINGS,
                        ItemAction.OPEN_CONTACTS,
                        ItemAction.OPEN_CALL_LOG,
                        ItemAction.OPEN_MESSAGING -> Unit
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
                    ItemAction.OPEN_MESSAGING,
                    ItemAction.WIFI_ON,
                    ItemAction.WIFI_OFF -> Unit
                }

                AppLog.toastOnMain("成功")
            } catch (t: Throwable) {
                AppLog.toastOnMain("${action.title}失败：${t.message ?: "未知错误"}")
            } finally {
                isBusy = false
            }
        }
    }

    /** 备份 / 覆盖备份共用：进度弹窗 + 结果处理，具体逻辑在 service 层。 */
    fun runBackup(
        groups: Set<BackupGroup>,
        overwrite: BackupEntry? = null,
        /** 一次逻辑操作的标识：重试沿用，主动新备份换新值。 */
        operationId: Long = System.currentTimeMillis(),
        resume: Boolean = false
    ) {
        // 冻结这次勾选和要覆盖的那条备份；失败时留着，从 tab 页「继续未完成」原样重跑
        if (!resume) {
            viewModel.setPending(
                PendingBatch(
                    action = if (overwrite == null) BatchAction.CREATE else BatchAction.OVERWRITE,
                    items = listOf(BatchItem(displayApp, groups, overwrite)),
                    operationId = operationId,
                    origin = BatchOrigin.DETAIL,
                    detailApp = displayApp,
                    detailFromBatch = fromBatch,
                    userId = currentUserId
                )
            )
        }
        showBackupOptions = false
        managingEntry = null
        backupError = null
        connectionInterrupted = false
        progressTitle = "正在备份"
        backupRunning = true
        showBackupProgress = true
        backupSteps.value = planner.backupSteps(groups)
        val steps = backupSteps.value
        // 通知会话绑定「备份 + 这个应用 + 1/1」，后面每次上报只传进度
        val session = NotifierSession(context, "备份", displayApp)
        session.start()

        scope.launch {
            val outcome = runner.run(
                steps = steps,
                onSteps = { backupSteps.value = it }
            ) { onProgress ->
                // 进度同时喂给通知：stepIndex 从初始步骤表里查，顺序不会变
                val report: (BackupProgress) -> Unit = { progress ->
                    onProgress(progress)
                    session.update(
                        progress,
                        steps.indexOfFirst { it.kind.key == progress.stage },
                        steps.size
                    )
                }
                if (overwrite == null) {
                    backupCase.backup(
                        displayApp, groups, report,
                        operationId = operationId,
                        // 详情页已经有条目列表：没到保留上限就不用为清理再查一次仓库
                        knownBackupCount = entries.size
                    ).onSuccess { cleaned ->
                        // 清理是收尾：说一句就够，没清理就完全静默
                        if (cleaned > 0) AppLog.toastOnMain("已清理 $cleaned 条旧备份")
                    }
                } else {
                    backupCase.overwrite(
                        displayApp, overwrite, groups, report,
                        operationId = operationId
                    ).map { 0 }
                }
            }

            backupRunning = false
            when (outcome) {
                OperationOutcome.Success -> {
                    session.finish(0, 1)
                    viewModel.clearPending()
                    reloadKey++
                    // 备份完只刷新备份数，不用重新拉整个应用列表
                    viewModel.refreshBackupInfo()
                }

                is OperationOutcome.Failed -> {
                    session.finish(1, 1)
                    backupError = outcome.error.message ?: "备份失败"
                    AppLog.e("备份", backupError ?: "备份失败", outcome.error)
                }

                is OperationOutcome.Interrupted -> {
                    session.abort()
                    connectionInterrupted = true
                    backupError = null
                    AppLog.e("备份", "远程仓库连接中断", outcome.error)
                }
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
            AppLog.toastOnMain("请先勾选要覆盖的内容")
            return
        }
        runBackup(groups, overwrite = entry)
    }

    /** 删除勾选的快照。 */
    fun deleteSelected(entry: BackupEntry) {
        val groups = managingKinds
        if (entry.selectedSnapshots(groups).isEmpty()) {
            AppLog.toastOnMain("请先勾选要删除的内容")
            return
        }

        managingEntry = null
        scope.launch {
            KeepAwake.acquire(context)
            try {
                writer.deleteSelected(entry, groups)
                    .onSuccess {
                        reloadKey++
                        // 删掉一条备份：只刷新列表里的备份数 / 备份时间
                        viewModel.refreshBackupInfo()
                    }
                    .onFailure {
                        AppLog.e("删除", "删除失败", it)
                        AppLog.toastOnMain("删除失败：${it.message ?: "未知错误"}")
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
                writer.deleteAll(displayApp.packageName)
                    .onSuccess { count ->
                        AppLog.toastOnMain(if (count == 0) "没有可删除的备份" else "删除成功")
                        reloadKey++
                        // 备份全删了，只在备份里存在的应用也要从列表里去掉，整表重读
                        viewModel.loadList(scrollToTop = false)
                    }
                    .onFailure {
                        AppLog.e("删除", "删除失败", it)
                        AppLog.toastOnMain("删除失败：${it.message ?: "未知错误"}")
                    }
            } finally {
                KeepAwake.release()
            }
        }
    }

    /** 恢复勾选的快照。 */
    fun restoreSelected(
        entry: BackupEntry,
        /** 重试时用冻结的勾选，不读当前 managingKinds。 */
        groups: Set<BackupGroup> = managingKinds,
        resume: Boolean = false
    ) {
        planner.restoreBlockedReason(entry, groups, isInstalled)?.let { reason ->
            AppLog.toastOnMain(reason)
            return
        }

        // 冻结用户选的那条备份（含版本和快照 id）与勾选类型，重试不能退回最新一条
        if (!resume) {
            viewModel.setPending(
                PendingBatch(
                    action = BatchAction.RESTORE,
                    items = listOf(BatchItem(displayApp, groups, entry)),
                    operationId = System.currentTimeMillis(),
                    origin = BatchOrigin.DETAIL,
                    detailApp = displayApp,
                    detailFromBatch = fromBatch,
                    userId = currentUserId
                )
            )
        }

        managingEntry = null
        backupSteps.value = planner.restoreSteps(entry, groups)
        val steps = backupSteps.value

        progressTitle = "正在恢复"
        backupError = null
        connectionInterrupted = false
        backupRunning = true
        showBackupProgress = true

        // 通知会话绑定「恢复 + 这个应用 + 1/1」
        val session = NotifierSession(context, "恢复", displayApp)
        session.start()

        scope.launch {
            val outcome = runner.run(
                steps = steps,
                onSteps = { backupSteps.value = it }
            ) { onProgress ->
                restoreCase.restore(
                    app = displayApp,
                    entry = entry,
                    groups = groups,
                    installed = isInstalled,
                    onProgress = { progress ->
                        onProgress(progress)
                        session.update(
                            progress,
                            steps.indexOfFirst { it.kind.key == progress.stage },
                            steps.size
                        )
                    }
                )
            }

            backupRunning = false
            when (outcome) {
                OperationOutcome.Success -> {
                    session.finish(0, 1)
                    viewModel.clearPending()
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

                is OperationOutcome.Failed -> {
                    session.finish(1, 1)
                    backupError = outcome.error.message ?: "恢复失败"
                    AppLog.e("恢复", backupError ?: "恢复失败", outcome.error)
                }

                is OperationOutcome.Interrupted -> {
                    session.abort()
                    connectionInterrupted = true
                    backupError = null
                    AppLog.e("恢复", "远程仓库连接中断", outcome.error)
                }
            }
        }
    }

    // 进入页面 / 备份结束后，按包名 tag 查一次仓库
    LaunchedEffect(displayApp.packageName, backupRunning, reloadKey) {
        if (backupRunning) return@LaunchedEffect
        val result = withContext(Dispatchers.IO) {
            reader.loadEntries(displayApp.packageName, displayApp.name)
        }
        result.onFailure { t ->
            if (BackendErrors.isConnectionLost(t)) {
                AppLog.toastOnMain(
                    t.message ?: BackendErrors.REMOTE_INTERRUPTED_MESSAGE,
                    longToast = true
                )
            }
        }
        entries = result.getOrElse { emptyList() }
    }

    // 从 tab 页「更多」菜单点「继续未完成」跳回来后自动续跑
    val resumeRequested by viewModel.resumeRequested.collectAsState()
    LaunchedEffect(resumeRequested) {
        if (!resumeRequested) return@LaunchedEffect
        viewModel.resumeRequested.value = false
        val pending = viewModel.pendingBatch.value ?: return@LaunchedEffect
        if (pending.origin != BatchOrigin.DETAIL) return@LaunchedEffect
        val item = pending.items.firstOrNull() ?: return@LaunchedEffect
        when (pending.action) {
            BatchAction.CREATE -> runBackup(item.kinds, null, pending.operationId, resume = true)
            BatchAction.OVERWRITE ->
                runBackup(item.kinds, item.entry, pending.operationId, resume = true)

            BatchAction.RESTORE -> item.entry?.let {
                restoreSelected(it, item.kinds, resume = true)
            }

            BatchAction.DELETE -> Unit
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
            writer.updateEntryDescription(entry, description)
                .onSuccess {
                    reloadKey++
                }.onFailure {
                    AppLog.e("备注", "修改备注失败", it)
                    AppLog.toastOnMain("修改备注失败：${it.message ?: "未知错误"}")
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
            ItemAction.OPEN_MESSAGING -> openSms(context)
            // 剩下的是 root 侧操作：破坏性的先确认，其余直接执行。
            // 「哪些要确认」不再在这里列一遍 —— 判据是 ItemAction.destructive，和菜单标红同源。
            ItemAction.CLEAR_DATA, ItemAction.UNINSTALL, ItemAction.DELETE_DIR,
            ItemAction.FORCE_STOP, ItemAction.FREEZE, ItemAction.UNFREEZE,
            ItemAction.WIFI_ON, ItemAction.WIFI_OFF ->
                if (action.destructive) confirmAction = action else runAction(action)
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
                // 用户切换：应用和文件夹都「属于某个用户」，换了他就是换到那个空间里的同一个目标
                showUserButton = target.isApp || target == BackupTarget.DIR,
                searchPlaceholder = "搜索备注",
                searchOpen = detailSearchOpen,
                searchQueryText = detailQuery,
                onToggleSearch = {
                    detailSearchOpen = !detailSearchOpen
                    if (!detailSearchOpen) detailQuery = ""
                },
                onSearchQueryChange = { detailQuery = it },
                moreMenuContent = { onDismiss ->
                    DetailMoreMenu(
                        canRemoveDir = onRemoveDir != null,
                        onDismiss = onDismiss,
                        onRequestRemoveDirConfirm = { confirmRemoveDir = true },
                        onRequestDeleteAllConfirm = { confirmDeleteAll = true }
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
                            target.countText(systemCounts)
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
        ConfirmActionDialog(
            title = "删除全部备份",
            message = "确定要删除「${displayApp.name}」的全部备份吗？此操作不可撤销。",
            onDismiss = { confirmDeleteAll = false },
            onConfirm = { deleteAllBackups() }
        )
    }

    // 移除文件夹确认：只退出备份目标，仓库里的备份保留
    if (confirmRemoveDir) {
        AppDialog(
            title = "移除该文件夹",
            onDismiss = { confirmRemoveDir = false },
            confirmText = "移除",
            onConfirm = {
                confirmRemoveDir = false
                onRemoveDir?.invoke()
            }
        ) { Text("「${displayApp.packageName}」将不再作为备份目标。") }
    }

    // 帮助对话框：内容随目标类型变（应用 / 文件 / Wi-Fi / 联系人 / 通话记录）
    if (showHelpDialog) {
        BackupHelpDialog(
            lines = TargetSpecs.of(target).helpLines,
            onDismiss = { showHelpDialog = false }
        )
    }

    managingEntry?.let { entry ->
        BackupManageDialog(
            entry = entry,
            target = target,
            isVersionChanged = displayApp.isVersionChangedFrom(entry.version, isInstalled),
            canOverwrite = planner.canOverwrite(isInstalled),
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
            // 用应用名而不是包名：进度弹窗顶部就这一行，给用户看的是「在备份谁」
            subtitle = displayApp.name.ifBlank { displayApp.packageName },
            sections = listOf(ProgressSection(title = "", steps = currentSteps)),
            isRestore = progressTitle != "正在备份",
            error = backupError,
            running = backupRunning,
            onDismiss = {
                showBackupProgress = false
                connectionInterrupted = false
            },
            connectionInterrupted = connectionInterrupted
        )
    }
}

/**
 * 详情页右上角「更多」菜单：移除文件夹（仅文件夹目标）+ 删除全部备份。
 *
 * 「移除文件夹」只退出备份目标、不删备份，但仍是状态变更（要回到列表里重新加回来），
 * 所以统一先弹一次确认，这里只负责发起请求。
 */
@Composable
private fun DetailMoreMenu(
    canRemoveDir: Boolean,
    onDismiss: () -> Unit,
    onRequestRemoveDirConfirm: () -> Unit,
    onRequestDeleteAllConfirm: () -> Unit,
) {
    if (canRemoveDir) {
        DropdownMenuItem(
            text = { Text("移除该文件夹") },
            onClick = {
                onDismiss()
                onRequestRemoveDirConfirm()
            }
        )
    }
    DropdownMenuItem(
        text = { Text("删除全部备份") },
        onClick = {
            onDismiss()
            onRequestDeleteAllConfirm()
        }
    )
}

// ==================== 应用操作实现 ====================

/**
 * 按操作类型生成确认弹窗的标题和正文。
 *
 * 标题直接取 [ItemAction.title]（菜单和弹窗用同一个词，改一处就够了）；
 * 正文才需要知道具体对象，所以按动作分。
 */
private fun confirmDialogText(
    action: ItemAction,
    appName: String,
    appPath: String
): Pair<String, String> = action.title to when (action) {
    ItemAction.DELETE_DIR -> "确定要删除「$appPath」吗？此操作不可撤销。"
    ItemAction.UNINSTALL -> "确定要卸载「$appName」吗？此操作不可撤销。"
    ItemAction.CLEAR_DATA -> "确定要清空「$appName」的全部数据吗？此操作不可撤销。"
    // 以后新增危险动作忘了写正文时，至少不会拿「清空数据」的文案去问用户
    else -> "确定要执行「${action.title}」吗？此操作不可撤销。"
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
        AppLog.toastOnMain("这个位置打不开")
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

    AppLog.toastOnMain("没有能打开文件夹的应用")
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
            AppLog.toastOnMain("该应用没有可启动的界面")
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure {
                AppLog.toastOnMain("启动失败：${it.message ?: "未知错误"}")
            }
        return
    }

    // 非主用户：用 LauncherApps 指定用户启动
    runCatching {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        val user = UserHandle.getUserHandleForUid(app.userId * 100_000)
        val info = launcherApps?.getActivityList(app.packageName, user)?.firstOrNull()
        if (info == null) {
            AppLog.toastOnMain("该应用没有可启动的界面")
        } else {
            launcherApps.startMainActivity(info.componentName, info.user, null, null)
        }
    }.onFailure {
        AppLog.toastOnMain("启动失败：${it.message ?: "未知错误"}")
    }
}

/** 打开系统的 Wi-Fi 设置页。 */
private fun openWifiSettings(context: Context) {
    val intent = Intent(Settings.ACTION_WIFI_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onFailure { AppLog.toastOnMain("打开 Wi-Fi 设置失败") }
}

/** 打开联系人应用 */
private fun openContacts(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure {
        AppLog.toastOnMain("${ItemAction.OPEN_CONTACTS.title} 失败")
    }
}

/** 打开通话记录。 */
private fun openCallLog(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, CallLog.Calls.CONTENT_URI)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure {
        AppLog.toastOnMain("${ItemAction.OPEN_CALL_LOG.title} 失败")
    }
}

/** 打开默认短信应用。*/
private fun openSms(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_APP_MESSAGING)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure {
        AppLog.toastOnMain("${ItemAction.OPEN_MESSAGING.title} 失败")
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
            AppLog.toastOnMain("打开系统设置失败")
        }
}

