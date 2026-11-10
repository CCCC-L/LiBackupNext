package com.cc.libackupnext.ui.dialog

import android.view.WindowManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.cc.libackupnext.data.FolderPickerMode
import com.cc.libackupnext.data.RemoteRepo
import com.cc.libackupnext.service.LocalVolumes
import com.cc.libackupnext.service.RcloneClient
import com.cc.libackupnext.ui.AppTopBar
import com.cc.libackupnext.ui.theme.dialogContainer
import com.cc.libackupnext.ui.theme.pageTransitionSpec
import com.cc.libackupnext.ui.util.AppListContainer
import kotlinx.coroutines.delay

/**
 * 通用文件夹选择：铺满全屏的独立 Dialog 窗口，由 MainActivity 按 launcher 显示。
 *
 * 本地 / 远程共用一套界面，只有数据来源不同：本地走 rclone 的 local 后端，
 * 远程走 [RemoteRepo] 派生出来的 inline 配置，二者都由 [RusticClient] 下发。
 */

/** 卷列表页的虚拟 path：不会和任何真实目录（最小是 "/"）撞上。 */
private const val VOLUME_PAGE = ""

/**
 * 目录页的导航键：只有进 / 退目录才变，刷新、新建、搜索都不改它。
 * [root] 是本页所属卷根的路径段，动画期间旧页用自己的 root 判断「还能不能再往上退」。
 */
private data class DirPage(val path: String, val root: List<String>, val depth: Int)

/** 一行：显示名、点击后要去的路径、可选副标题（卷路径 / 只读）。 */
private data class PickerRow(
    val name: String,
    val target: String,
    val subtitle: String? = null
)

/** 每个目录自己的列表快照；旧页滑出期间靠它保持内容，不会闪成加载圈。 */
private data class DirContent(
    val rows: List<PickerRow>? = null,
    val error: String? = null
) {
    val loading: Boolean get() = rows == null && error == null
}

/** 把 rclone 返回的子目录名拼回绝对路径；远程的 path 也是同一套斜杠写法。 */
private fun joinPath(base: String, name: String): String =
    if (base == "/") "/$name" else "$base/$name"

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FolderPickerDialog(
    mode: FolderPickerMode,
    repo: RemoteRepo?,
    initialPath: String,
    allowRoot: Boolean,
    onPick: (String) -> Unit,
    onBack: () -> Unit
) {
    val isLocal = mode == FolderPickerMode.LOCAL
    // 当前选中卷的根（路径段）；选「根目录」时是空列表
    var volumeRoot by remember { mutableStateOf(emptyList<String>()) }
    // 本地一律从卷列表开始，远程仍按 initialPath / 仓库路径进目录
    val remoteStart = initialPath.ifBlank { repo?.path?.ifBlank { "/" } ?: "/" }
    val initialParts = remember(remoteStart) {
        remoteStart.trim('/').split('/').filter { it.isNotEmpty() }
    }
    // 只记「有没有点过目录」：没点过就按返回直接关弹窗，点过才逐级回父目录
    var hasClicked by remember { mutableStateOf(false) }
    // 动画的 targetState：只在进 / 退目录时变，depth 决定滑动方向
    var page by remember {
        mutableStateOf(
            if (isLocal) DirPage(VOLUME_PAGE, emptyList(), 0)
            else DirPage("/" + initialParts.joinToString("/"), emptyList(), initialParts.size)
        )
    }
    // 每个 path 一份列表快照，弹窗关闭即释放
    val contents = remember { mutableStateMapOf<String, DirContent>() }
    var refresh by remember { mutableStateOf(0) }
    var pendingCreate by remember { mutableStateOf<String?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    fun closeSearch() {
        searching = false
        query = ""
    }

    fun toggleSearch() {
        if (searching) closeSearch() else searching = true
    }

    /** 进 / 退目录：更新动画页面，点击瞬间就播转场；换目录一律收起搜索栏。 */
    fun goTo(target: String, root: List<String> = volumeRoot) {
        closeSearch()
        if (target == VOLUME_PAGE) {
            page = DirPage(VOLUME_PAGE, emptyList(), 0)
            return
        }
        val parts = target.trim('/').split('/').filter { it.isNotEmpty() }
        // 从面包屑跳到当前卷之外（allowRoot 时点「根目录」）就按根目录这一支重算层级，
        // 否则 depth 会算成负数、返回方向也跟着错
        val effectiveRoot = if (isLocal && parts.take(root.size) != root) emptyList() else root
        volumeRoot = effectiveRoot
        // 本地把卷列表算作第 0 层、卷根第 1 层，不同卷的根深度都一样；
        // 远程直接按路径段数，服务根 "/" 是第 0 层
        val depth = if (isLocal) 1 + (parts.size - effectiveRoot.size) else parts.size
        page = DirPage("/" + parts.joinToString("/"), effectiveRoot, depth)
    }

    /**
     * 上一级去哪：本地已经到卷根（或文件系统根 "/"）就回卷列表，更深的目录回父目录；
     * 远程服务根 "/" 没有上一级，其余回父目录。只读本页的 root / path，动画期间不跳级。
     */
    fun parentTarget(target: DirPage): String? {
        if (target.path == VOLUME_PAGE) return null
        val parts = target.path.trim('/').split('/').filter { it.isNotEmpty() }
        if (isLocal && parts.size <= target.root.size) return VOLUME_PAGE
        if (parts.isEmpty()) return null
        return "/" + parts.dropLast(1).joinToString("/")
    }

    /** initialPath 是否落在某个卷里，用来在点击该卷时直接回到原目录。 */
    fun isUnder(path: String, root: String): Boolean {
        val r = root.trimEnd('/')
        return path == r || path.startsWith("$r/")
    }

    val canGoUp = parentTarget(page) != null

    // Dialog 是独立窗口，返回键 / 点外部 / 顶栏返回箭头都走这里；BackHandler 在这里不可靠
    fun handleBack() {
        when {
            searching -> closeSearch()
            // 点过目录才逐级回父目录（卷根回卷列表）；没点过、或已退回卷列表就关弹窗
            hasClicked && canGoUp -> {
                parentTarget(page)?.let { goTo(it) }
            }

            else -> onBack()
        }
    }

    LaunchedEffect(page.path, repo, mode, refresh) {
        val create = pendingCreate
        pendingCreate = null
        val targetPath = page.path
        // 同目录内刷新 / 新建：只原地显示加载圈，不重放转场动画
        contents[targetPath] = DirContent()
        val result: Result<List<PickerRow>> = when {
            // 本地第一屏：只向业务层要卷列表，UI 不分类、不排序、不拼路径
            isLocal && targetPath == VOLUME_PAGE -> runCatching {
                LocalVolumes.list(allowRoot).map { vol ->
                    PickerRow(
                        name = vol.label,
                        target = vol.path,
                        // 卷列表只显示名字，不把绝对路径露出来；只读卷另加一行提示
                        subtitle = if (vol.readOnly) "只读" else null
                    )
                }
            }

            isLocal -> RcloneClient.listLocalDirs(targetPath, create)
                .map { dirs -> dirs.map { PickerRow(it, joinPath(targetPath, it)) } }

            else -> (repo?.let { RcloneClient.listRemoteDirs(it, targetPath, create) }
                ?: Result.failure<List<String>>(IllegalStateException("缺少远程服务配置")))
                .map { dirs -> dirs.map { PickerRow(it, joinPath(targetPath, it)) } }
        }
        // 快速连点时旧请求可能后回来，只写回还在显示的那一级目录
        result
            .onSuccess {
                if (page.path == targetPath) contents[targetPath] = DirContent(rows = it)
            }
            .onFailure {
                if (page.path == targetPath) {
                    contents[targetPath] = DirContent(error = it.message ?: "列目录失败")
                }
            }
    }

    Dialog(
        onDismissRequest = { handleBack() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            // 背景铺到系统栏后面，内容自己用 statusBarsPadding / navigationBarsPadding 让开
            decorFitsSystemWindows = false
        )
    ) {
        // Compose Dialog 的窗口高度默认是 WRAP_CONTENT，光靠 fillMaxSize 拉不满，
        // 显式把窗口设成 MATCH_PARENT x MATCH_PARENT
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        LaunchedEffect(Unit) {
            dialogWindow?.let { w ->
                w.setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT
                )
                // scrim 自己画（状态栏那一条也要是阴影色），关掉系统 dim 免得叠成两层；
                // 状态栏图标压在阴影上，改用浅色才看得清
                w.setDimAmount(0f)
                runCatching {
                    WindowCompat.getInsetsController(w, w.decorView)
                        .isAppearanceLightStatusBars = false
                }
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxSize()
                // 先铺一层遮罩阴影：状态栏区域、顶部圆角之外露出的都是它
                .background(Color.Black.copy(alpha = 0.32f))
                // 再让弹窗本身从状态栏下方开始
                .statusBarsPadding(),
            shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            // 内容只需避开导航栏 / 键盘，状态栏已由 Surface 的 padding 让开
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                AppTopBar(
                    title = "选择文件夹",
                    pageBackground = MaterialTheme.colorScheme.surfaceContainerLow,
                    searchBarBackground = MaterialTheme.colorScheme.surfaceContainerHigh,
                    navigationIcon = {
                        IconButton(onClick = { handleBack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    // 搜索状态完全由弹窗自己管，不读写主页面的全局搜索流
                    searchPlaceholder = "搜索文件夹",
                    searchOpen = searching,
                    searchQueryText = query,
                    onToggleSearch = { toggleSearch() },
                    onSearchQueryChange = { query = it },
                    // Dialog 是独立窗口，Surface 已让开状态栏，别让 TopAppBar 再吃一次 inset
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    // 排序 / 用户切换 / 更多菜单对目录浏览都没用，换成自己的两个按钮；
                    // 搜索按钮由 AppTopBar 统一渲染，展开搜索栏时在槽位内擦除，
                    // 右边缘贴着新建按钮，不会飞到屏幕右边
                    customActions = {
                        // 卷列表页没有「当前目录」，不能新建文件夹
                        IconButton(
                            onClick = {
                                newName = ""
                                showCreate = true
                            },
                            enabled = page.path != VOLUME_PAGE
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "添加文件夹")
                        }
                        IconButton(onClick = { refresh++ }) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新")
                        }
                    }
                )

                // 当前路径跟着目录切换淡入淡出；刷新 / 新建时 path 不变，不会重放
                AnimatedContent(
                    targetState = page.path,
                    transitionSpec = {
                        fadeIn(animationSpec = tween(200)) togetherWith
                                fadeOut(animationSpec = tween(200))
                    },
                    label = "FolderPathTransition"
                ) { path ->
                    // 本地把绝对路径拆成可点面包屑：卷根段显示别名，其余段逐层可跳。
                    // 按本页 path 算，旧页滑出期间不会串成新路径
                    val segments = remember(path, allowRoot, isLocal) {
                        if (isLocal && path != VOLUME_PAGE) {
                            LocalVolumes.pathSegments(path, allowRoot)
                        } else {
                            emptyList()
                        }
                    }
                    when {
                        path == VOLUME_PAGE -> Text(
                            text = "选择存储位置",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp)
                        )

                        // 远程仍是服务内的绝对路径，没有卷别名这一层
                        !isLocal -> Text(
                            text = path,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp)
                        )

                        else -> Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 22.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            segments.forEachIndexed { index, segment ->
                                if (index > 0) {
                                    Text(
                                        text = "/",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 4.dp)
                                    )
                                }
                                val isCurrent = index == segments.lastIndex
                                Text(
                                    text = segment.label,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isCurrent) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    },
                                    maxLines = 1,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .clickable {
                                            hasClicked = true
                                            // 回「选择存储位置」页走列表里的「..」
                                            goTo(segment.path)
                                            // 点当前目录等同原地刷新（page 不变不会重跑加载）
                                            if (segment.path == path) refresh++
                                        }
                                        .padding(horizontal = 3.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }

                // 列表铺满剩余空间，底部留出悬浮按钮的空间
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    AnimatedContent(
                        targetState = page,
                        // 层级越深越靠右：进入子目录从右滑入，返回父目录反向，
                        // 和 AppListScreen 的页面切换用同一套规格
                        transitionSpec = pageTransitionSpec<DirPage> { it.depth },
                        label = "FolderPageTransition",
                        modifier = Modifier.fillMaxSize().clipToBounds()
                    ) { targetPage ->
                        // 只读本页的快照：旧页滑出期间不会被新目录的加载态顶掉
                        val snapshot = contents[targetPage.path] ?: DirContent()
                        val visibleRows = remember(snapshot.rows, query) {
                            snapshot.rows.orEmpty().filter {
                                query.isBlank() || it.name.contains(query, ignoreCase = true) ||
                                        it.subtitle?.contains(query, ignoreCase = true) == true
                            }
                        }
                        Box(modifier = Modifier.fillMaxSize()) {
                            when {
                                snapshot.loading -> LoadingIndicator(
                                    modifier = Modifier.align(Alignment.Center)
                                )

                                snapshot.error != null -> Column(
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = snapshot.error.orEmpty(),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                    TextButton(onClick = { refresh++ }) { Text("重试") }
                                }

                                visibleRows.isEmpty() && query.isNotBlank() -> Text(
                                    text = if (targetPage.path == VOLUME_PAGE) {
                                        "没有匹配的存储位置"
                                    } else {
                                        "没有匹配的文件夹"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.align(Alignment.Center)
                                )

                                else -> {
                                    // 有上一级才给「..」：卷列表页和远程服务根都没有。
                                    // 用本页的 depth 判断，旧页滑出时列表不会跳动
                                    val rows = buildList {
                                        parentTarget(targetPage)?.let {
                                            add(PickerRow("..", it))
                                        }
                                        addAll(visibleRows)
                                    }
                                    // 圆角直接用 AppListContainer 算好的 shape（首尾 extraLarge、中间 extraSmall），
                                    // 和应用 tab 页的列表完全一致
                                    AppListContainer(
                                        items = rows,
                                        modifier = Modifier.fillMaxSize(),
                                        contentPadding = PaddingValues(
                                            start = 16.dp,
                                            end = 16.dp,
                                            top = 8.dp,
                                            bottom = 100.dp
                                        ),
                                        itemContent = { _, row, shape ->
                                            Surface(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        hasClicked = true
                                                        if (targetPage.path == VOLUME_PAGE) {
                                                            // 从卷列表进卷：先记下卷根，再落到卷根
                                                            // 或 initialPath（编辑已有仓库时少走几步）
                                                            val root = row.target
                                                                .trim('/')
                                                                .split('/')
                                                                .filter { it.isNotEmpty() }
                                                            val landing = if (
                                                                row.target != "/" &&
                                                                initialPath.isNotBlank() &&
                                                                isUnder(initialPath, row.target)
                                                            ) initialPath else row.target
                                                            goTo(landing, root)
                                                        } else {
                                                            // 目录页：行里的 target 就是本页推导出来的，
                                                            // 动画期间再点也不会基于最新页面多跳一级
                                                            goTo(row.target)
                                                        }
                                                    },
                                                shape = shape,
                                                color = MaterialTheme.colorScheme.surface
                                            ) {
                                                Column(
                                                    modifier = Modifier.padding(
                                                        horizontal = 16.dp,
                                                        vertical = 14.dp
                                                    )
                                                ) {
                                                    Text(
                                                        text = row.name,
                                                        style = MaterialTheme.typography.titleMedium,
                                                        color = MaterialTheme.colorScheme.onSurface,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    if (row.subtitle != null) {
                                                        Text(
                                                            text = row.subtitle,
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                            modifier = Modifier.padding(top = 2.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // 悬浮按钮：没有底栏背景、没有分隔线，直接浮在列表上。
                    // 卷列表页还不是一个可选目录，不显示
                    if (page.path != VOLUME_PAGE) {
                        Button(
                            onClick = { onPick(page.path) },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth(0.6f)
                                .padding(bottom = 16.dp),
                            shape = MaterialTheme.shapes.large,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            contentPadding = PaddingValues(vertical = 14.dp)
                        ) {
                            Text("使用此文件夹", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
    }

    // 新建文件夹：弹出即聚焦输入框并拉起键盘，按钮用 AlertDialog 自带的右对齐文字按钮
    if (showCreate) {
        val focusRequester = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
            // 二级 Dialog 也是新窗口，等它真正拿到焦点再 show，否则键盘偶发不弹
            delay(100)
            keyboard?.show()
        }
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.dialogContainer,
            onDismissRequest = { showCreate = false },
            title = { Text("新建文件夹") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("文件夹名称") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = newName.trim()
                        if (name.isNotEmpty()) {
                            pendingCreate = name
                            showCreate = false
                            refresh++
                        }
                    },
                    enabled = newName.isNotBlank()
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showCreate = false }) { Text("取消") }
            }
        )
    }
}
