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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cc.libackupnext.data.AppBackupSummary
import com.cc.libackupnext.data.BackupEntry
import com.cc.libackupnext.data.BackupGroup
import com.cc.libackupnext.data.BackupStep
import com.cc.libackupnext.data.BackupTarget
import com.cc.libackupnext.data.kind
import com.cc.libackupnext.entity.AppInfo
import com.cc.libackupnext.service.BackupProgress
import com.cc.libackupnext.service.RusticClient
import com.cc.libackupnext.ui.AppTopBar
import com.cc.libackupnext.ui.HomeTab
import com.cc.libackupnext.ui.MainViewModel
import com.cc.libackupnext.ui.SortMenuExtra
import com.cc.libackupnext.ui.dialog.BackupHelpDialog
import com.cc.libackupnext.ui.dialog.BackupProgressDialog
import com.cc.libackupnext.ui.dialog.ProgressSection
import com.cc.libackupnext.ui.dialog.rememberDoubleClickGuard
import com.cc.libackupnext.ui.theme.LocalAppColors
import com.cc.libackupnext.ui.theme.rememberRowSelectionColor
import com.cc.libackupnext.ui.theme.rememberRowSwipeState
import com.cc.libackupnext.ui.util.AppIconStore
import com.cc.libackupnext.ui.util.AppListContainer
import com.cc.libackupnext.ui.util.TargetIconBox
import com.cc.libackupnext.ui.util.dimmedWhen
import com.cc.libackupnext.ui.util.formatShortDateTime
import com.cc.libackupnext.ui.util.isMissing
import com.cc.libackupnext.ui.util.missingTitleColor
import com.cc.libackupnext.ui.util.systemAppTitleColor
import com.cc.libackupnext.util.BackupNotifier
import com.cc.libackupnext.util.ErrorLog
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.roundToInt

/** 勾选列的宽度：列头和数据行保持一致才能对齐。 */
private val KindColumnWidth = 34.dp

/** 勾选框本体尺寸（默认 48dp 太大，表格式排布用小的）。 */
private val KindCheckboxSize = 28.dp

/**
 * 备份里的版本和当前装的版本是否不一致（备份过之后应用又更新过）。
 * 只在备份里、本机没装的目标没有「当前版本」可比，一律不算。
 */
private fun AppInfo.isVersionChanged(summary: AppBackupSummary?): Boolean {
    if (onlyInBackup) return false
    val backedUp = summary?.latest?.version ?: return false
    return backedUp != version
}

/** 这一页的两种用途。 */
enum class BackupBatchMode {
    /** 新建应用备份：列出所有已安装应用。 */
    CREATE,

    /** 管理应用备份：只列出仓库里有备份的应用，可批量删除 / 覆盖 / 恢复。 */
    MANAGE
}

/**
 * 批量备份 / 批量管理页面。
 *
 * 两种模式共用同一套列表：勾选框、统计、底部操作按钮，
 * 区别只在数据来源（全部应用 / 有备份的应用）、默认勾选和底部按钮。
 *
 * @param tab 从哪个页签进来的：决定要列哪些目标（应用 / 文件各一个，其他页三个），
 *   也决定勾选状态存在哪一份 —— 应用页和文件页的勾选互不影响
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupBatchScreen(
    viewModel: MainViewModel,
    mode: BackupBatchMode,
    tab: HomeTab,
    onBack: () -> Unit,
    onSwitchMode: () -> Unit,
    onOpenAppDetail: (AppInfo) -> Unit,
    listState: LazyListState
) {
    val targets = tab.targets
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
    // 列头只在一个目标有多种内容时才有意义：应用页是 all + apk/user/data 四个勾选框，
    // 文件夹只有 dir 一个、其他页每个目标各一个 kind，都只留 all。
    // 所以「多目标 → 空列头」，每行的勾选框另按自己 target 的 kinds 画。
    val columnKinds = targets.singleOrNull()?.kinds.orEmpty()
    val unit = targets.singleOrNull()?.unit ?: "项"

    var overview by remember { mutableStateOf(viewModel.batchOverviewCache) }
    var reloadKey by remember { mutableStateOf(0) }

    // 包名 -> 勾选的类型；按「页签 + 模式」分开存，进出详情页不丢、页签之间互不影响
    val selected = viewModel.batchSelection(tab, mode)

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
    var deletedKinds by remember { mutableStateOf<Map<String, Set<BackupGroup>>>(emptyMap()) }

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

    // 倒序：应用行已经由 displayApps 排好，文件夹行要在这里单独反转
    val isReverse by viewModel.isReverse.collectAsState()

    // 这一页的行：目标 + 展示数据。多目标页就是几个目标各自的行拼起来。
    // 管理页只显示仓库里有备份的。
    val rows = remember(displayApps, overview, mode, targets, isReverse) {
        targets.flatMap { target ->
            val list = target.targets(
                apps = displayApps,
                create = mode == BackupBatchMode.CREATE,
                hasBackup = { overview.containsKey(it) }
            ).map { TargetRow(target, it) }
            // 应用行来自 displayApps（已经按 sortType + 倒序排过）；
            // 文件夹行直接读 AppPrefs.extraDirs，得在这里单独反转。
            if (isReverse && target == BackupTarget.DIR) list.asReversed() else list
        }
    }

    // 包名 -> 它属于哪个目标。选中状态都按包名索引，需要目标时查这张表
    // （应用是包名、目录是路径、「其他」是 wifi / calls / contacts，不会撞）
    val targetOf = remember(rows) { rows.associate { it.item.packageName to it.target } }

    val apps = remember(rows) { rows.map { it.item } }

    // 每个批量页用自己的搜索状态，不影响 applist
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // 排序菜单的「置顶不同版本」：只影响这一页的显示顺序，不动主列表
    val pinDifferent by viewModel.pinDifferentVersions.collectAsState()

    val visibleApps = remember(apps, searchQuery, pinDifferent, overview) calc@{
        val query = searchQuery.trim()
        val filtered = if (query.isEmpty()) {
            apps
        } else {
            apps.filter {
                it.name.contains(query, ignoreCase = true) ||
                        it.packageName.contains(query, ignoreCase = true)
            }
        }
        if (!pinDifferent) return@calc filtered

        // 备份时的版本和现在装的不一致 → 排最前面，方便找出「备份过之后又更新过」的应用。
        filtered.sortedByDescending { it.isVersionChanged(overview[it.packageName]) }
    }

    // 置顶开关一变直接回到顶部，
    LaunchedEffect(pinDifferent) {
        if (visibleApps.isNotEmpty()) listState.scrollToItem(0)
    }

    // displayApps 为空时（应用列表还没加载完）也要重跑一次，否则默认全选选不到东西
    LaunchedEffect(reloadKey, displayApps.isEmpty()) {
        val names = allApps.associate { it.packageName to it.name }
        val list = RusticClient.backupOverview(names)
        overview = list.associateBy { it.packageName }
        viewModel.batchOverviewCache = overview
        // 概况已经是仓库的最新状态，临时记录可以清掉了
        deletedKinds = emptyMap()

        // 从详情页选了一条备份回来：把这条备份补进勾选，其它应用保持原样
        val pending = viewModel.pendingSingleSelection
        if (pending != null) {
            viewModel.pendingSingleSelection = null
            selected[pending.first] = pending.second
            viewModel.markBatchSelectionInitialized(tab, BackupBatchMode.MANAGE)
            return@LaunchedEffect
        }

        // 新建页：默认全选只做一次（用户取消后不会再被选上）
        if (mode == BackupBatchMode.CREATE && !viewModel.isBatchSelectionInitialized(tab, mode)) {
            val allRows = targets.flatMap { target ->
                target.targets(
                    apps = displayApps,
                    create = true,
                    hasBackup = { overview.containsKey(it) }
                ).map { TargetRow(target, it) }
            }
            if (allRows.isEmpty()) return@LaunchedEffect

            allRows.forEach { selected[it.item.packageName] = it.target.kinds.toSet() }
            viewModel.markBatchSelectionInitialized(tab, mode)
            return@LaunchedEffect
        }

        // 管理页：默认勾上「仓库里已有的类型」，同样只做一次
        if (mode == BackupBatchMode.MANAGE && !viewModel.isBatchSelectionInitialized(tab, mode)) {
            viewModel.markBatchSelectionInitialized(tab, mode)
            selected.clear()
            list.forEach { summary ->
                val kinds = summary.latest.availableKinds
                    .filter { it in targetOf[summary.packageName]?.kinds.orEmpty() }
                    .toSet()
                if (kinds.isNotEmpty()) selected[summary.packageName] = kinds
            }
        }
    }

    fun kindsOf(packageName: String): Set<BackupGroup> = selected[packageName].orEmpty()

    /** 这个应用要操作的那条备份：详情页里明确选过的优先，否则用最新的。 */
    fun entryOf(packageName: String): BackupEntry? =
        viewModel.selectedEntries[packageName] ?: overview[packageName]?.latest

    /** 这一行属于哪个目标。行是从 [rows] 来的，一定查得到。 */
    fun targetOfRow(packageName: String): BackupTarget =
        targetOf[packageName] ?: targets.first()

    /** 这个应用当前能勾哪些类型：管理页只有仓库里确实存在的类型可勾。 */
    fun availableKindsOf(packageName: String): Set<BackupGroup> {
        val kinds = targetOfRow(packageName).kinds
        return if (mode == BackupBatchMode.CREATE) {
            kinds.toSet()
        } else {
            entryOf(packageName)?.availableKinds
                .orEmpty()
                .filter { it in kinds }
                .filterNot { it in deletedKinds[packageName].orEmpty() }
                .toSet()
        }
    }

    fun setKinds(packageName: String, kinds: Set<BackupGroup>) {
        if (kinds.isEmpty()) {
            selected.remove(packageName)
            // 整行取消勾选后不再沿用详情页里选的那条备份
            viewModel.selectedEntries.remove(packageName)
        } else {
            selected[packageName] = kinds
        }
    }

    fun toggleKind(app: AppInfo, group: BackupGroup) {
        if (group !in availableKindsOf(app.packageName)) return
        val current = kindsOf(app.packageName)
        setKinds(app.packageName, if (group in current) current - group else current + group)
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
    fun toggleColumn(targets: List<AppInfo>, group: BackupGroup) {
        val rows = targets.filter { group in availableKindsOf(it.packageName) }
        val allChecked = rows.isNotEmpty() && rows.all { group in kindsOf(it.packageName) }
        rows.forEach { app ->
            val current = kindsOf(app.packageName)
            setKinds(app.packageName, if (allChecked) current - group else current + group)
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

    /** 多选：按条件从当前可见列表里挑出一批，结果为空时只提示、不进多选模式。 */
    fun selectMatched(emptyMessage: String, predicate: (AppInfo) -> Boolean) {
        val matched = visibleApps.filter(predicate).mapTo(HashSet()) { it.packageName }

        if (matched.isEmpty()) {
            Toast.makeText(context, emptyMessage, Toast.LENGTH_SHORT).show()
            return
        }

        multiSelect = true
        anchorPackage = null
        multiSelected = matched
    }

    /**
     * 多选：只勾选「有备份」或「没备份」的那些。
     *
     * 主要给新建备份页用：已备份的拿来覆盖，没备份的拿来补。
     */
    fun selectByBackup(backedUp: Boolean) {
        selectMatched(if (backedUp) "没有已备份的" else "没有未备份的") {
            overview.containsKey(it.packageName) == backedUp
        }
    }

    /**
     * 多选：只勾选「有备份，但备份时的版本和现在装的不一样」的那些。
     *
     * 用来快速找出「备份过之后又更新过」的应用：这些应用的备份还是旧版本。
     * 版本取最近一次备份的 tag（[BackupEntry.version]），备份时就是 AppInfo.version 原样写进去的。
     */
    fun selectUpdated() {
        selectMatched("没有已更新的应用") {
            it.isVersionChanged(overview[it.packageName])
        }
    }

    /** 多选：当前可见的应用取反。 */
    fun invertSelection() {
        val visible = visibleApps.mapTo(HashSet()) { it.packageName }
        val inverted = visible.filterNot { it in multiSelected }
        multiSelected = (multiSelected - visible) + inverted
    }

    /** 这次操作的目标应用 + 类型：多选模式下只取选中的应用。 */
    fun targets(): List<Pair<AppInfo, Set<BackupGroup>>> = apps.mapNotNull { app ->
        if (multiSelect && app.packageName !in multiSelected) return@mapNotNull null
        val kinds = kindsOf(app.packageName)
        if (kinds.isEmpty()) null else app to kinds
    }

    /**
     * 批量执行，逐个应用跑 [action]。
     *
     * 进度弹窗和应用详情页共用：显示当前应用的步骤，副标题是「3/10 应用名」。
     *
     * @param onFinished 整批跑完后调一次。用来做「一批只做一次」的收尾
     */
    fun runBatch(
        title: String,
        doneTitle: String,
        isRestore: Boolean,
        list: List<Pair<AppInfo, Set<BackupGroup>>>,
        stepsOf: (AppInfo, Set<BackupGroup>) -> List<BackupStep>,
        onFinished: (suspend (successCount: Int) -> Unit)? = null,
        action: suspend (AppInfo, Set<BackupGroup>, (BackupProgress) -> Unit) -> Result<Unit>
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
            try {
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
                                last.copy(steps = RusticClient.applyProgress(last.steps, progress))

                        val stepIndex = appSteps.indexOfFirst { it.kind.key == progress.stage }
                        BackupNotifier.update(
                            context = context,
                            action = actionLabel,
                            appName = app.name,
                            packageName = app.packageName,
                            userId = app.userId,
                            appIndex = finished + 1,
                            appTotal = total,
                            stageLabel = progress.stage,
                            stageIndex = if (stepIndex >= 0) stepIndex + 1 else 1,
                            stageTotal = appSteps.size
                        )
                    }.onFailure {
                        failed++
                        ErrorLog.add(progressTitle, "${app.name} 失败", it)
                        progressError = it.message ?: "操作失败"
                        val last = progressSections.last()
                        progressSections = progressSections.dropLast(1) +
                                last.copy(steps = RusticClient.markFailed(last.steps))
                    }
                    finished++
                }
            } finally {
                // 宿主销毁（转屏 / 切深色模式）会把 scope 一起取消，
                // 已经备份成功的那些应用仍然需要这份 apk，收尾不能跟着丢
                if (onFinished != null) {
                    withContext(NonCancellable) { onFinished(total - failed) }
                }
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
                viewModel.loadList(scrollToTop = false)
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
            stepsOf = { _, kinds -> RusticClient.backupSteps(kinds) },
            onFinished = { successCount ->
                if (successCount > 0) RusticClient.exportSelfApk()
            }
        ) { app, kinds, onProgress ->
            // 图标有没有备份过，概况里已经带了，不用每个应用再查一次仓库
            RusticClient.backup(
                app = app,
                groups = kinds,
                onProgress = onProgress,
                batchId = batchId,
                iconAlreadyBackedUp = overview[app.packageName]?.hasIcon,
                // 同类型最新快照的 id：给 rustic 当 parent，省掉它自己列一遍全部快照
                parentIds = overview[app.packageName]?.latest?.snapshots
                    ?.mapNotNull { snapshot -> snapshot.kind()?.let { it to snapshot.id } }
                    ?.toMap()
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

            RusticClient.deleteSelected(entry, kinds).onSuccess {
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
        val batchId = UUID.randomUUID().toString()

        runBatch(
            title = "正在覆盖备份",
            doneTitle = "覆盖成功",
            isRestore = false,
            list = targets(),
            stepsOf = { _, kinds -> RusticClient.backupSteps(kinds) },
            onFinished = { successCount ->
                if (successCount > 0) RusticClient.exportSelfApk()
            }
        ) { app, kinds, onProgress ->
            val entry = entryOf(app.packageName)
                ?: return@runBatch Result.failure(IllegalStateException("没有可覆盖的备份"))
            RusticClient.overwrite(app, entry, kinds, onProgress, batchId)
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
                if (entry == null) emptyList() else RusticClient.restoreSteps(entry, kinds)
            }
        ) { app, kinds, onProgress ->
            val entry = entryOf(app.packageName)
                ?: return@runBatch Result.failure(IllegalStateException("没有可恢复的备份"))
            RusticClient.restore(app, entry, kinds, installed = !app.onlyInBackup, onProgress = onProgress)
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

    // 统计文字里的类型：单目标页就是列头那几列；多目标页是这一页所有目标的 kinds
    val summaryKinds = columnKinds.ifEmpty { targets.flatMap { it.kinds }.distinct() }
    val selectedCounts = summaryKinds.associateWith { kind ->
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
                // 用户切换只跟应用有关，文件批量页不显示
                // 只有应用页需要切用户
                showUserButton = targets.any { it.isApp },
                // 系统数据页是固定三行，没有可排序的内容
                showSortMenu = tab != HomeTab.OTHERS,
                // 文件夹页只留「倒序」；只有应用页需要四种排序方式
                showSortTypes = targets.any { it.isApp },
                // 排序菜单的第三项：应用页能按「版本变了」置顶，文件夹 / 系统数据页没有版本可比
                sortMenuExtra = if (targets.any { it.isApp }) {
                    SortMenuExtra.PIN_DIFFERENT_VERSIONS
                } else {
                    SortMenuExtra.NONE
                },
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
                        text = { Text("选中已备份的") },
                        onClick = {
                            onDismiss()
                            selectByBackup(backedUp = true)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("选中未备份的") },
                        onClick = {
                            onDismiss()
                            selectByBackup(backedUp = false)
                        }
                    )
                    // 只有应用页才有「版本更新」这回事：文件夹 / 系统数据没有版本可比。
                    // 新建和管理都放 —— 管理页同样需要快速挑出「备份是旧版本」的那批去删或恢复。
                    if (targets.any { it.isApp }) {
                        DropdownMenuItem(
                            text = { Text("选中已更新的应用") },
                            onClick = {
                                onDismiss()
                                selectUpdated()
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (mode == BackupBatchMode.CREATE) {
                                    "切换到管理备份"
                                } else {
                                    "切换到新建备份"
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
                columns = columnKinds,
                unit = unit,
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
                    target = targetOfRow(app.packageName),
                    summary = overview[app.packageName],
                    entry = entryOf(app.packageName),
                    showBackupTime = mode == BackupBatchMode.MANAGE,
                    kinds = kindsOf(app.packageName),
                    availableKinds = availableKindsOf(app.packageName),
                    // 列头只在单目标页显示；每行的勾选框永远按它自己的 target 画
                    columns = columnKinds.ifEmpty { targetOfRow(app.packageName).kinds },
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
            lines = listOf(
                "选项：点击图标反选整行",
                "多选：右滑某个应用进入多选，再右滑另一个应用会选中两者之间的全部应用",
                "多选模式下点应用只切换选中、不进详情，顶部的复选框、底部的按钮也只作用于选中的应用",
            ) + targets.flatMap { it.helpLines }.distinct(),
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
    counts: Map<BackupGroup, Int>,
    currentUser: String,
    /** 这一页有哪些勾选单位（apk/user/data 或 dir）。 */
    columns: List<BackupGroup>,
    /** 统计文字里的单位：「个应用」/「个文件夹」。 */
    unit: String,
    /** 多选里一个都没选时，这四个勾选框没有作用对象，置灰。 */
    enabled: Boolean,
    allChecked: Boolean,
    columnChecked: (BackupGroup) -> Boolean,
    onToggleAll: () -> Unit,
    onToggleColumn: (BackupGroup) -> Unit,
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
                text = if (hasBackupOnly) "$appCount ${unit}有备份" else "$appCount $unit",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(1.dp))
            Text(
                text = if (selectedApps == 0) {
                    "已选择: " + counts.entries.joinToString(" ") { "${it.key.title}:0" }
                } else {
                    "已选择: " + counts.entries.joinToString(" ") { "${it.key.title}:${it.value}" }
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
        // 只有一种内容时（例如目录的 dir），「all」和那一列是一回事，只留 all
        if (columns.size > 1) columns.forEach { kind ->
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
                    text = kind.title,
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
    target: BackupTarget,
    summary: AppBackupSummary?,
    /** 要展示的那条备份（详情页里选过的优先）。 */
    entry: BackupEntry?,
    showBackupTime: Boolean,
    kinds: Set<BackupGroup>,
    availableKinds: Set<BackupGroup>,
    /** 这一页要画哪几列勾选框。 */
    columns: List<BackupGroup>,
    shape: Shape,
    /** 多选模式：点击只切换选中、不显示水波纹。 */
    multiSelect: Boolean,
    /** 在多选集合里：整行蓝底。 */
    selected: Boolean,
    /** 备份进行中时不允许滑动改选中。 */
    swipeEnabled: Boolean,
    onToggleKind: (BackupGroup) -> Unit,
    onToggleApp: () -> Unit,
    onOpenDetail: () -> Unit,
    onSwipeSelect: () -> Unit
) {
    val appColors = LocalAppColors.current

    // 应用没装 / 文件夹被删：和列表页一样，图标置灰 + 标题划线
    val missing = target.isMissing(app)

    // 备份时的版本和现在装的不一致：版本号标蓝，一眼看出「这个应用的备份是旧版本」。
    // 没装的应用（missing）不算 —— 它的 version 本来就是从备份里读出来的，比不出差异。
    val versionChanged = !missing && app.isVersionChanged(summary)

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
            Box(modifier = Modifier.dimmedWhen(missing)) {
                // 批量页不查仓库里的图标，省一次「有没有 icon 快照」的查询
                TargetIconBox(target, app, size = 40.dp, useBackupIcon = false)
            }
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
                        withStyle(
                            SpanStyle(
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Normal,
                                // 版本不一致时高亮
                                color = if (versionChanged) {
                                    appColors.subtitleColor
                                } else {
                                    Color.Unspecified
                                }
                            )
                        ) {
                            append(app.version)
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = systemAppTitleColor(target.isApp && app.isSystem)
                        ?: missingTitleColor(missing),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (missing) TextDecoration.LineThrough else null
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
                if (target.isApp && !showBackupTime) {
                    Spacer(modifier = Modifier.height(1.dp))
                    Text(
                        text = "更新于 ${formatShortDateTime(app.updateTime)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                if (showBackupTime && entry != null) {
                    Spacer(modifier = Modifier.height(1.dp))
                    Text(
                        text = "备份于 ${formatShortDateTime(entry.time)}",
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
                columns.forEach { kind ->
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
