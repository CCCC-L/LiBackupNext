package com.cc.libackupnext.ui.screen

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cc.libackupnext.data.AppBackupSummary
import com.cc.libackupnext.data.BackupEntry
import com.cc.libackupnext.data.SnapshotMeta
import com.cc.libackupnext.data.BackupStep
import com.cc.libackupnext.entity.AppInfo
import com.cc.libackupnext.service.BackupProgress
import com.cc.libackupnext.service.RusticService
import com.cc.libackupnext.util.BackupNotifier
import com.cc.libackupnext.util.ErrorLog
import com.cc.libackupnext.ui.util.AppIcon
import com.cc.libackupnext.ui.util.AppIconStore
import com.cc.libackupnext.ui.util.AppListContainer
import com.cc.libackupnext.ui.AppTopBar
import com.cc.libackupnext.ui.dialog.BackupHelpDialog
import com.cc.libackupnext.ui.dialog.BackupProgressDialog
import com.cc.libackupnext.ui.MainViewModel
import com.cc.libackupnext.ui.dialog.ProgressSection
import com.cc.libackupnext.ui.util.formatShortDateTime
import com.cc.libackupnext.ui.util.rememberAppIcon
import com.cc.libackupnext.ui.dialog.rememberDoubleClickGuard
import com.cc.libackupnext.ui.theme.LocalAppColors
import com.cc.libackupnext.ui.theme.rememberRowSelectionColor
import com.cc.libackupnext.ui.theme.rememberRowSwipeState
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.collections.plus
import java.util.UUID

/** 新建备份页里每个应用可勾选的内容，顺序与列表列头一致。 */
private val BackupKinds = listOf("apk", "user", "data")

/** 勾选列的宽度：列头和数据行保持一致才能对齐。 */
private val KindColumnWidth = 34.dp

/** 勾选框本体尺寸（默认 48dp 太大，表格式排布用小的）。 */
private val KindCheckboxSize = 28.dp

/** 这一页的两种用途。 */
enum class BackupBatchMode {
    /** 新建应用备份：列出所有已安装应用。 */
    CREATE,

    /** 管理应用备份：只列出仓库里有备份的应用，可批量删除 / 覆盖 / 恢复。 */
    MANAGE
}

@Composable
fun AppBackupScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onSwitchMode: () -> Unit,
    onOpenAppDetail: (AppInfo) -> Unit,
    listState: LazyListState = rememberLazyListState()
) = BackupBatchScreen(
    viewModel, BackupBatchMode.CREATE, onBack, onSwitchMode, onOpenAppDetail, listState
)

@Composable
fun ManageBackupScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onSwitchMode: () -> Unit,
    onOpenAppDetail: (AppInfo) -> Unit,
    listState: LazyListState = rememberLazyListState()
) = BackupBatchScreen(
    viewModel, BackupBatchMode.MANAGE, onBack, onSwitchMode, onOpenAppDetail, listState
)

/**
 * 批量备份 / 批量管理页面。
 *
 * 两种模式共用同一套列表：勾选框、统计、底部操作按钮，
 * 区别只在数据来源（全部应用 / 有备份的应用）、默认勾选和底部按钮。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackupBatchScreen(
    viewModel: MainViewModel,
    mode: BackupBatchMode,
    onBack: () -> Unit,
    onSwitchMode: () -> Unit,
    onOpenAppDetail: (AppInfo) -> Unit,
    listState: LazyListState
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val displayApps by viewModel.displayApps.collectAsState()
    val allApps by viewModel.allApps.collectAsState()
    val users by viewModel.users.collectAsState()
    val currentUserId by viewModel.currentUserId.collectAsState()

    // 当前用户名字，找不到就用「用户 id」
    val currentUserName = remember(users, currentUserId) {
        users.firstOrNull { it.id == currentUserId }
            ?.name
            ?.takeIf { it.isNotBlank() }
            ?: "用户 $currentUserId"
    }

    // 包名 -> 备份概况（备份次数 / 最近一次备份）
    // 先用上次缓存渲染，避免返回页面时列表先空一帧（空列表会把滚动位置归零）
    var overview by remember { mutableStateOf(viewModel.batchOverviewCache) }
    var reloadKey by remember { mutableStateOf(0) }

    // 包名 -> 勾选的类型；放 ViewModel 里，进出应用详情页不丢，两个页面各自独立
    val selected = if (mode == BackupBatchMode.CREATE) {
        viewModel.createSelection
    } else {
        viewModel.manageSelection
    }

    var running by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(0) }
    var total by remember { mutableStateOf(0) }
    var progressTitle by remember { mutableStateOf("正在备份") }
    var progressDoneTitle by remember { mutableStateOf("备份成功") }
    var progressSubtitle by remember { mutableStateOf("") }
    var progressSections by remember { mutableStateOf<List<ProgressSection>>(emptyList()) }
    var progressRestore by remember { mutableStateOf(false) }
    var progressError by remember { mutableStateOf<String?>(null) }
    var showProgress by remember { mutableStateOf(false) }

    // 删除 / 覆盖备份要双击确认
    val doubleClickGuard = rememberDoubleClickGuard()

    /**
     * 本次操作里已经删掉的类型：包名 -> 类型集合。
     *
     * 删完之后立刻把对应的复选框变灰（仓库里已经没有这个类型了），
     * 不用等备份概况重新加载。
     */
    var deletedKinds by remember { mutableStateOf<Map<String, Set<String>>>(emptyMap()) }

    // ==================== 多选模式 ====================
    /** 多选模式：右滑动某一行进入。 */
    var multiSelect by remember { mutableStateOf(false) }

    /** 多选里的行（包名）。 */
    var multiSelected by remember { mutableStateOf<Set<String>>(emptySet()) }

    /** 多选锚点：最开始滑动的那一行。 */
    var anchorPackage by remember { mutableStateOf<String?>(null) }

    fun exitMultiSelect() {
        multiSelect = false
        multiSelected = emptySet()
        anchorPackage = null
    }

    // 管理页只显示仓库里有备份的应用
    val apps = remember(displayApps, overview, mode) {
        val installed = displayApps.filterNot { mode == BackupBatchMode.CREATE && it.onlyInBackup }
        if (mode == BackupBatchMode.CREATE) installed
        else installed.filter { overview.containsKey(it.packageName) }
    }

    // 每个批量页用自己的搜索状态，不影响 applist
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val visibleApps = remember(apps, searchQuery) {
        val query = searchQuery.trim()
        if (query.isEmpty()) {
            apps
        } else {
            apps.filter {
                it.name.contains(query, ignoreCase = true) ||
                        it.packageName.contains(query, ignoreCase = true)
            }
        }
    }

    // displayApps 为空时（应用列表还没加载完）也要重跑一次，否则默认全选选不到东西
    LaunchedEffect(reloadKey, displayApps.isEmpty()) {
        val names = allApps.associate { it.packageName to it.name }
        val list = RusticService.backupOverview(names)
        overview = list.associateBy { it.packageName }
        viewModel.batchOverviewCache = overview
        // 概况已经是仓库的最新状态，临时记录可以清掉了
        deletedKinds = emptyMap()

        // 从详情页选了一条备份回来：把这条备份补进勾选，其它应用保持原样
        val pending = viewModel.pendingSingleSelection
        if (pending != null) {
            viewModel.pendingSingleSelection = null
            selected[pending.first] = pending.second
            viewModel.manageSelectionInitialized = true
            return@LaunchedEffect
        }

        // 新建页：默认全选只做一次（用户取消后不会再被选上）
        if (mode == BackupBatchMode.CREATE && !viewModel.createSelectionInitialized) {
            val targets = displayApps.filterNot { it.onlyInBackup }
            if (targets.isEmpty()) return@LaunchedEffect

            targets.forEach { app -> selected[app.packageName] = BackupKinds.toSet() }
            viewModel.createSelectionInitialized = true
            return@LaunchedEffect
        }

        // 管理页：默认勾上「仓库里已有的类型」，同样只做一次
        if (mode == BackupBatchMode.MANAGE && !viewModel.manageSelectionInitialized) {
            viewModel.manageSelectionInitialized = true
            selected.clear()
            list.forEach { summary ->
                val kinds = summary.latest.availableKinds.filter { it in BackupKinds }.toSet()
                if (kinds.isNotEmpty()) selected[summary.packageName] = kinds
            }
        }
    }

    fun kindsOf(packageName: String): Set<String> = selected[packageName].orEmpty()

    /** 这个应用要操作的那条备份：详情页里明确选过的优先，否则用最新的。 */
    fun entryOf(packageName: String): BackupEntry? =
        viewModel.selectedEntries[packageName] ?: overview[packageName]?.latest

    /** 这个应用当前能勾哪些类型：管理页只有仓库里确实存在的类型可勾。 */
    fun availableKindsOf(packageName: String): Set<String> =
        if (mode == BackupBatchMode.CREATE) {
            BackupKinds.toSet()
        } else {
            entryOf(packageName)?.availableKinds
                .orEmpty()
                .filter { it in BackupKinds }
                .filterNot { it in deletedKinds[packageName].orEmpty() }
                .toSet()
        }

    fun setKinds(packageName: String, kinds: Set<String>) {
        if (kinds.isEmpty()) {
            selected.remove(packageName)
            // 整行取消勾选后不再沿用详情页里选的那条备份
            viewModel.selectedEntries.remove(packageName)
        } else {
            selected[packageName] = kinds
        }
    }

    fun toggleKind(app: AppInfo, kind: String) {
        if (kind !in availableKindsOf(app.packageName)) return
        val current = kindsOf(app.packageName)
        setKinds(app.packageName, if (kind in current) current - kind else current + kind)
    }

    fun toggleApp(app: AppInfo) {
        val available = availableKindsOf(app.packageName)
        val current = kindsOf(app.packageName)
        setKinds(
            app.packageName,
            if (current.containsAll(available)) emptySet() else available
        )
    }

    /** 多选模式下点一行：在 / 不在多选里切换。右边的三个勾选框保持原样。 */
    fun toggleInMultiSelect(app: AppInfo) {
        val packageName = app.packageName
        multiSelected = if (packageName in multiSelected) {
            multiSelected - packageName
        } else {
            multiSelected + packageName
        }
    }

    /**
     * 右滑某一行（只认右滑）。
     */
    fun onRowSwiped(app: AppInfo) {
        val anchorIndex = visibleApps.indexOfFirst { it.packageName == anchorPackage }
        val index = visibleApps.indexOfFirst { it.packageName == app.packageName }

        // 还没进多选，或者锚点已经不在当前列表里（换了搜索词等）：这一行当新锚点
        if (!multiSelect || anchorIndex < 0 || index < 0) {
            multiSelect = true
            multiSelected = multiSelected + app.packageName
            anchorPackage = app.packageName
            return
        }

        // 区间内（含两端）全部加入多选
        var next = multiSelected
        for (i in minOf(anchorIndex, index)..maxOf(anchorIndex, index)) {
            next = next + visibleApps[i].packageName
        }
        multiSelected = next
    }

    /** 列头勾选：整列全选 / 全不选（只影响 [targets] 里确实有这一列的应用）。 */
    fun toggleColumn(targets: List<AppInfo>, kind: String) {
        val rows = targets.filter { kind in availableKindsOf(it.packageName) }
        val allChecked = rows.isNotEmpty() && rows.all { kind in kindsOf(it.packageName) }
        rows.forEach { app ->
            val current = kindsOf(app.packageName)
            setKinds(app.packageName, if (allChecked) current - kind else current + kind)
        }
    }

    /** 整行勾选：[targets] 全勾 / 全不勾。 */
    fun toggleAll(targets: List<AppInfo>) {
        val allChecked = targets.isNotEmpty() &&
                targets.all { kindsOf(it.packageName).containsAll(availableKindsOf(it.packageName)) }
        targets.forEach { app ->
            setKinds(app.packageName, if (allChecked) emptySet() else availableKindsOf(app.packageName))
        }
    }

    /** 多选：当前可见的应用全选；已经是全选就取消全选。 */
    fun selectAllVisible() {
        val visible = visibleApps.mapTo(HashSet()) { it.packageName }
        val allSelected = visible.isNotEmpty() && multiSelected.containsAll(visible)
        multiSelected = if (allSelected) multiSelected - visible else multiSelected + visible
    }

    /** 多选：当前可见的应用取反。 */
    fun invertSelection() {
        val visible = visibleApps.mapTo(HashSet()) { it.packageName }
        val inverted = visible.filterNot { it in multiSelected }
        multiSelected = (multiSelected - visible) + inverted
    }

    /** 这次操作的目标应用 + 类型：多选模式下只取选中的应用。 */
    fun targets(): List<Pair<AppInfo, Set<String>>> = apps.mapNotNull { app ->
        if (multiSelect && app.packageName !in multiSelected) return@mapNotNull null
        val kinds = kindsOf(app.packageName)
        if (kinds.isEmpty()) null else app to kinds
    }

    /**
     * 批量执行，逐个应用跑 [action]。
     *
     * 进度弹窗和应用详情页共用：显示当前应用的步骤，副标题是「3/10 应用名」。
     */
    fun runBatch(
        title: String,
        doneTitle: String,
        isRestore: Boolean,
        list: List<Pair<AppInfo, Set<String>>>,
        stepsOf: (AppInfo, Set<String>) -> List<BackupStep>,
        action: suspend (AppInfo, Set<String>, (BackupProgress) -> Unit) -> Result<Unit>
    ) {
        if (list.isEmpty()) {
            Toast.makeText(context, "请先勾选要操作的内容", Toast.LENGTH_SHORT).show()
            return
        }
        progressTitle = title
        progressDoneTitle = doneTitle
        progressRestore = isRestore
        progressError = null
        progressSubtitle = "开始:${list.first().first.packageName}"
        progressSections = emptyList()
        running = true
        showProgress = true
        total = list.size
        finished = 0

        val actionLabel = title.removePrefix("正在")
        BackupNotifier.start(
            context = context,
            action = actionLabel,
            appName = list.first().first.name,
            packageName = list.first().first.packageName,
            userId = list.first().first.userId,
            appIndex = 1,
            appTotal = list.size
        )

        scope.launch {
            var failed = 0
            for ((app, kinds) in list) {
                val appSteps = stepsOf(app, kinds)
                progressSubtitle = if (total > 1) {
                    "${finished + 1}/$total  ${app.name}"
                } else {
                    "开始:${app.packageName}"
                }
                // 每个应用追加一段，之前成功的记录保留在窗口里
                progressSections = progressSections + ProgressSection(
                    title = app.name,
                    steps = appSteps
                )
                BackupNotifier.update(
                    context = context,
                    action = actionLabel,
                    appName = app.name,
                    packageName = app.packageName,
                    userId = app.userId,
                    appIndex = finished + 1,
                    appTotal = total,
                    stageLabel = "",
                    stageIndex = 0,
                    stageTotal = appSteps.size
                )

                action(app, kinds) { progress ->
                    val last = progressSections.last()
                    progressSections = progressSections.dropLast(1) +
                            last.copy(steps = RusticService.applyProgress(last.steps, progress))

                    val stepIndex = appSteps.indexOfFirst { it.key == progress.stage }
                    BackupNotifier.update(
                        context = context,
                        action = actionLabel,
                        appName = app.name,
                        packageName = app.packageName,
                        userId = app.userId,
                        appIndex = finished + 1,
                        appTotal = total,
                        stageLabel = BackupNotifier.stageLabel(progress.stage),
                        stageIndex = if (stepIndex >= 0) stepIndex + 1 else 1,
                        stageTotal = appSteps.size
                    )
                }.onFailure {
                    failed++
                    ErrorLog.add(progressTitle, "${app.name} 失败", it)
                    progressError = it.message ?: "操作失败"
                    val last = progressSections.last()
                    progressSections = progressSections.dropLast(1) +
                            last.copy(steps = RusticService.markFailed(last.steps))
                }
                finished++
            }
            running = false
            progressSubtitle = if (failed == 0) {
                "完成 $total/$total"
            } else {
                "完成 ${total - failed}/$total，失败 $failed 个"
            }
            BackupNotifier.finish(context, actionLabel, failed, total)
            // 勾选保留，方便连续操作
            reloadKey++

            // 备份 / 覆盖不改应用集合，只刷新备份数；删除 / 恢复可能改变，需要整表重读
            if (actionLabel == "备份" || actionLabel == "覆盖备份") {
                viewModel.refreshBackupInfo()
            } else {
                // 恢复可能装上了应用：清掉图标的负缓存，否则还是首字母占位块
                if (actionLabel == "恢复") AppIconStore.invalidateMissing()
                viewModel.loadApps(scrollToTop = false)
            }
        }
    }

    fun startCreate() {
        // 整批共用一个批次标识：仓库只打开一次（scrypt + 读索引 563ms），
        // 后面的应用直接复用，几十个应用能省几十秒
        val batchId = UUID.randomUUID().toString()

        runBatch(
            title = "正在备份",
            doneTitle = "备份成功",
            isRestore = false,
            list = targets(),
            stepsOf = { _, kinds -> RusticService.backupSteps(kinds) }
        ) { app, kinds, onProgress ->
            // 图标有没有备份过，概况里已经带了，不用每个应用再查一次仓库
            RusticService.backup(
                app = app,
                kinds = kinds,
                onProgress = onProgress,
                batchId = batchId,
                iconAlreadyBackedUp = overview[app.packageName]?.hasIcon,
                // 同类型最新快照的 id：给 rustic 当 parent，省掉它自己列一遍全部快照
                parentIds = overview[app.packageName]?.latest?.snapshots
                    ?.associate { SnapshotMeta.kind(it.label) to it.id }
            )
        }
    }

    fun startDelete() {
        runBatch(
            title = "正在删除",
            doneTitle = "删除成功",
            isRestore = false,
            list = targets(),
            stepsOf = { _, _ -> emptyList() }
        ) { app, kinds, _ ->
            val entry = entryOf(app.packageName)
                ?: return@runBatch Result.failure(IllegalStateException("没有可删除的备份"))

            RusticService.deleteSelected(entry, kinds).onSuccess {
                // 删掉的类型立刻取消勾选并禁止再勾：仓库里已经没有它们了
                selected[app.packageName] = kindsOf(app.packageName) - kinds
                deletedKinds = deletedKinds +
                        (app.packageName to deletedKinds[app.packageName].orEmpty() + kinds)
                // 详情页选过来的那条备份已经变了，别再用它算「可勾选类型」
                viewModel.selectedEntries.remove(app.packageName)
            }
        }
    }

    fun startOverwrite() {
        runBatch(
            title = "正在覆盖备份",
            doneTitle = "覆盖成功",
            isRestore = false,
            list = targets(),
            stepsOf = { _, kinds -> RusticService.backupSteps(kinds) }
        ) { app, kinds, onProgress ->
            val entry = entryOf(app.packageName)
                ?: return@runBatch Result.failure(IllegalStateException("没有可覆盖的备份"))
            RusticService.overwrite(app, entry, kinds, onProgress)
        }
    }

    fun startRestore() {
        runBatch(
            title = "正在恢复",
            doneTitle = "恢复成功",
            isRestore = true,
            list = targets(),
            stepsOf = { app, kinds ->
                val entry = entryOf(app.packageName)
                if (entry == null) emptyList() else RusticService.restoreSteps(entry, kinds)
            }
        ) { app, kinds, onProgress ->
            val entry = entryOf(app.packageName)
                ?: return@runBatch Result.failure(IllegalStateException("没有可恢复的备份"))
            RusticService.restore(app, entry, kinds, installed = !app.onlyInBackup, onProgress = onProgress)
        }
    }

    val isDark = isSystemInDarkTheme()
    val pageBackground =
        if (isDark) MaterialTheme.colorScheme.surfaceContainerLow else Color(0xFFEAEEF7)
    val searchBarBackground =
        if (isDark) MaterialTheme.colorScheme.surfaceContainerHighest else Color(0xFFE2E7F1)

    // 多选模式优先响应返回：先退出多选，再退页面
    BackHandler(enabled = multiSelect && !running) { exitMultiSelect() }

    // 备份进行中不允许离开，避免请求被打断
    BackHandler(enabled = running) { }

    var showHelp by remember { mutableStateOf(false) }

    // 页面被销毁（切用户等）时别把前台服务和通知留在通知栏
    DisposableEffect(Unit) {
        onDispose { if (running) BackupNotifier.cancel(context) }
    }

    val selectedCounts = BackupKinds.associateWith { kind ->
        apps.count { kind in kindsOf(it.packageName) }
    }
    val selectedApps = selected.size

    // 底部按钮能不能点：多选模式下只看选中的应用里有没有可操作的
    val actionableCount = if (multiSelect) {
        apps.count { it.packageName in multiSelected && kindsOf(it.packageName).isNotEmpty() }
    } else {
        selectedApps
    }

    // 顶部四个列头勾选框的作用范围：多选模式下只作用于多选里的行，否则是当前可见的全部行
    val controlledApps = if (multiSelect) {
        visibleApps.filter { it.packageName in multiSelected }
    } else {
        visibleApps
    }


    Scaffold(
        containerColor = pageBackground,
        topBar = {
            AppTopBar(
                viewModel = viewModel,
                title = if (mode == BackupBatchMode.CREATE) "新建备份" else "管理备份",
                searchOpen = searchOpen,
                searchQueryText = searchQuery,
                onToggleSearch = {
                    searchOpen = !searchOpen
                    if (!searchOpen) searchQuery = ""
                },
                onSearchQueryChange = { searchQuery = it },
                pageBackground = pageBackground,
                searchBarBackground = searchBarBackground,
                // 多选时顶栏变成「已选择 N 项 + 全选 / 反选」
                selectionMode = multiSelect,
                selectionCount = multiSelected.size,
                allSelected = visibleApps.isNotEmpty() &&
                        visibleApps.all { it.packageName in multiSelected },
                onSelectAll = { selectAllVisible() },
                onInvert = { invertSelection() },
                navigationIcon = {
                    // 多选中点返回先退出多选，再退页面
                    IconButton(
                        onClick = { if (multiSelect) exitMultiSelect() else onBack() },
                        enabled = !running
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = if (multiSelect) "退出多选" else "返回"
                        )
                    }
                },
                // 两个批量页互相切换，不再显示「新建 / 管理」入口
                moreMenuContent = { onDismiss ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (mode == BackupBatchMode.CREATE) {
                                    "切换到管理应用备份"
                                } else {
                                    "切换到新建应用备份"
                                }
                            )
                        },
                        onClick = {
                            onDismiss()
                            onSwitchMode()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("帮助") },
                        onClick = {
                            onDismiss()
                            showHelp = true
                        }
                    )
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // 统计 + 列头勾选框固定在列表上方，不跟着滚动
            SelectionHeader(
                appCount = visibleApps.size,
                hasBackupOnly = mode == BackupBatchMode.MANAGE,
                selectedApps = selectedApps,
                counts = selectedCounts,
                currentUser = currentUserName,
                enabled = !multiSelect || controlledApps.isNotEmpty(),
                allChecked = controlledApps.isNotEmpty() &&
                        controlledApps.all {
                            kindsOf(it.packageName).containsAll(availableKindsOf(it.packageName))
                        },
                columnChecked = { kind ->
                    val rows = controlledApps.filter { kind in availableKindsOf(it.packageName) }
                    rows.isNotEmpty() && rows.all { kind in kindsOf(it.packageName) }
                },
                onToggleAll = { toggleAll(controlledApps) },
                onToggleColumn = { kind -> toggleColumn(controlledApps, kind) },
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            AppListContainer(
                items = visibleApps,
                modifier = Modifier.weight(1f),
                state = listState,
                // 顶部不留白，紧贴 header
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 0.dp,
                    bottom = 8.dp
                ),
                key = { _, app -> app.packageName }
            ) { _, app, shape ->
                BackupSelectRow(
                    app = app,
                    summary = overview[app.packageName],
                    entry = entryOf(app.packageName),
                    showBackupTime = mode == BackupBatchMode.MANAGE,
                    kinds = kindsOf(app.packageName),
                    availableKinds = availableKindsOf(app.packageName),
                    shape = shape,
                    multiSelect = multiSelect,
                    selected = app.packageName in multiSelected,
                    swipeEnabled = !running,
                    onToggleKind = { toggleKind(app, it) },
                    onToggleApp = { if (multiSelect) toggleInMultiSelect(app) else toggleApp(app) },
                    // 多选模式下这一列不再可点，点击交给整行（只切换选中）
                    onOpenDetail = { onOpenAppDetail(app) },
                    onSwipeSelect = { onRowSwiped(app) }
                )
            }

            if (mode == BackupBatchMode.CREATE) {
                Button(
                    onClick = {
                        if (doubleClickGuard.consume("create")) startCreate()
                    },
                    enabled = actionableCount > 0 && !running,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .height(52.dp)
                ) {
                    Text("备份", style = MaterialTheme.typography.titleMedium)
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = {
                            if (doubleClickGuard.consume("delete")) startDelete()
                        },
                        enabled = actionableCount > 0 && !running,
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier
                            .weight(0.9f)
                            .height(40.dp)
                    ) {
                        Text(
                            "删除",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    FilledTonalButton(
                        onClick = {
                            if (doubleClickGuard.consume("overwrite")) startOverwrite()
                        },
                        enabled = actionableCount > 0 && !running,
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier
                            .weight(1.1f)
                            .height(40.dp)
                    ) {
                        Text("覆盖备份", style = MaterialTheme.typography.bodyMedium)
                    }
                    FilledTonalButton(
                        onClick = {
                            if (doubleClickGuard.consume("restore")) startRestore()
                        },
                        enabled = actionableCount > 0 && !running,
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier
                            .weight(1.4f)
                            .height(40.dp)
                    ) {
                        Text("恢复", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }

    if (showProgress) {
        BackupProgressDialog(
            title = progressTitle,
            doneTitle = progressDoneTitle,
            subtitle = progressSubtitle,
            sections = progressSections,
            isRestore = progressRestore,
            error = progressError,
            running = running,
            onDismiss = { showProgress = false }
        )
    }

    if (showHelp) {
        BackupHelpDialog(
            extraLines = listOf(
                "选项：点击图标反选整行",
                "多选：右滑某个应用进入多选，再右滑另一个应用会选中两者之间的全部应用",
                "多选模式下点应用只切换选中、不进详情，顶部的 all / apk / user / data、底部的按钮 也只作用于选中的应用",
            ),
            onDismiss = { showHelp = false }
        )
    }
}

/** 列表上方的统计 + 列头勾选框。多选模式下这四个勾选框只作用于已选中的行。 */
@Composable
private fun SelectionHeader(
    appCount: Int,
    hasBackupOnly: Boolean,
    selectedApps: Int,
    counts: Map<String, Int>,
    currentUser: String,
    /** 多选里一个都没选时，这四个勾选框没有作用对象，置灰。 */
    enabled: Boolean,
    allChecked: Boolean,
    columnChecked: (String) -> Boolean,
    onToggleAll: () -> Unit,
    onToggleColumn: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (hasBackupOnly) "$appCount 个应用有备份" else "$appCount 个应用",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(1.dp))
            Text(
                text = if (selectedApps == 0) {
                    "已选择: apk:0 user:0 data:0"
                } else {
                    "已选择: " + BackupKinds.joinToString(" ") { "$it:${counts[it] ?: 0}" }
                },
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(1.dp))
            Text(
                text = "当前用户: $currentUser",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Column(
            modifier = Modifier.width(KindColumnWidth),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Checkbox(
                checked = allChecked,
                onCheckedChange = { onToggleAll() },
                enabled = enabled,
                modifier = Modifier.size(KindCheckboxSize)
            )
            Text(
                text = "all",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        BackupKinds.forEach { kind ->
            Column(
                modifier = Modifier.width(KindColumnWidth),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Checkbox(
                    checked = columnChecked(kind),
                    onCheckedChange = { onToggleColumn(kind) },
                    enabled = enabled,
                    modifier = Modifier.size(KindCheckboxSize)
                )
                Text(
                    text = kind,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 一行：图标 + 名称/版本 + 备份数/包名 + 三个勾选框。多选选中时整行变蓝底。 */
@Composable
private fun BackupSelectRow(
    app: AppInfo,
    summary: AppBackupSummary?,
    /** 要展示的那条备份（详情页里选过的优先）。 */
    entry: BackupEntry?,
    showBackupTime: Boolean,
    kinds: Set<String>,
    availableKinds: Set<String>,
    shape: Shape,
    /** 多选模式：点击只切换选中、不显示水波纹。 */
    multiSelect: Boolean,
    /** 在多选集合里：整行蓝底。 */
    selected: Boolean,
    /** 备份进行中时不允许滑动改选中。 */
    swipeEnabled: Boolean,
    onToggleKind: (String) -> Unit,
    onToggleApp: () -> Unit,
    onOpenDetail: () -> Unit,
    onSwipeSelect: () -> Unit
) {
    val appColors = LocalAppColors.current

    // 右滑选中：跟手位移 + 松手弹回（动画参数在 theme/Animation.kt）
    val swipe = rememberRowSwipeState(onSwipe = onSwipeSelect)

    // 多选选中时整行蓝底
    val rowColor by rememberRowSelectionColor(
        selected = multiSelect && selected,
        selectedColor = appColors.rowSelectedBackground,
        normalColor = MaterialTheme.colorScheme.surface
    )

    // 多选里点击直接选中，不要水波纹
    val rowClick = if (multiSelect) {
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onToggleApp
        )
    } else {
        Modifier.clickable(onClick = onToggleApp)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .offset { IntOffset(swipe.offsetPx.roundToInt(), 0) }
            .draggable(
                state = swipe.draggableState,
                orientation = Orientation.Horizontal,
                enabled = swipeEnabled,
                onDragStopped = { swipe.release() }
            ),
        shape = shape,
        color = rowColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(rowClick)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIcon(
                icon = rememberAppIcon(app.packageName, app.userId),
                name = app.name,
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))

            // 点这一块进应用详情页；点行的其他地方是勾选。多选模式下整行都只切换选中
            Column(
                modifier = Modifier
                    .weight(1f)
                    .then(if (multiSelect) Modifier else Modifier.clickable(onClick = onOpenDetail))
            ) {
                // 应用名加粗、版本号正常字重且更小；显示不下就省略
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                            append(app.name)
                        }
                        append("  ")
                        withStyle(SpanStyle(fontSize = 10.sp, fontWeight = FontWeight.Normal)) {
                            append(app.version)
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(1.dp))
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(1.dp))
                Text(
                    text = "${summary?.count ?: 0}个备份",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                if (showBackupTime && entry != null) {
                    Spacer(modifier = Modifier.height(1.dp))
                    Text(
                        text = formatShortDateTime(entry.time),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                    // 有备注就显示在时间下面
                    entry.description?.takeIf { it.isNotBlank() }?.let { note ->
                        Spacer(modifier = Modifier.height(1.dp))
                        Text(
                            text = note,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.End) {
                BackupKinds.forEach { kind ->
                    Box(
                        modifier = Modifier.width(KindColumnWidth),
                        contentAlignment = Alignment.Center
                    ) {
                        Checkbox(
                            checked = kind in kinds,
                            onCheckedChange = { onToggleKind(kind) },
                            enabled = kind in availableKinds,
                            modifier = Modifier.size(KindCheckboxSize)
                        )
                    }
                }
            }
        }
    }
}
