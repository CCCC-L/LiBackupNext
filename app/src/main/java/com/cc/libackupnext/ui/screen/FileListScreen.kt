package com.cc.libackupnext.ui.screen

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.cc.libackupnext.data.AppBackupSummary
import com.cc.libackupnext.data.BackupTarget
import com.cc.libackupnext.data.FolderPickerLauncher
import com.cc.libackupnext.data.FolderPickerMode
import com.cc.libackupnext.data.dirAppInfo
import com.cc.libackupnext.util.AppLog
import java.io.File

/**
 * 文件页：列出要备份的目录。
 *
 * 默认 4 个（DCIM / Download / Music / Pictures），右下角的圆形按钮打开全屏文件夹选择
 * 弹窗再挑别的目录。点一行进这个目录的「文件信息」页，快照 label 是 `<绝对路径>:dir`。
 */
@Composable
fun FileListScreen(
    dirs: List<String>,
    summaries: Map<String, AppBackupSummary>,
    /** 每次从后台回到前台递增，用来重算哪些目录还在硬盘上 */
    refreshTick: Int,
    /** 搜索词：按完整路径或文件夹名匹配，空串不过滤 */
    query: String,
    listState: LazyListState,
    onOpen: (String) -> Unit,
    onAdd: (String) -> Unit,
    /** 打开通用文件夹选择弹窗；这里允许一路退到文件系统根 "/"。 */
    onOpenFolderPicker: (FolderPickerLauncher) -> Unit = {},
    modifier: Modifier = Modifier,
    /** 倒序：顶栏排序菜单里唯一对文件夹列表生效的开关。 */
    reverse: Boolean = false,
    /** 下拉刷新：重算目录存在性 + 刷新备份数，具体动作由调用方给 */
    isRefreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
) {
    // 文件 tab 的 + 号允许一路退到 /：备份目标不一定是内部存储里的目录
    val pickFolder: () -> Unit = {
        onOpenFolderPicker(
            FolderPickerLauncher(
                mode = FolderPickerMode.LOCAL,
                allowRoot = true,
                onPick = { path ->
                    when {
                        path in dirs -> if (File(path).isDirectory) {
                            AppLog.toastOnMain("已经在列表里了")
                        } else {
                            onAdd(path)
                        }

                        else -> onAdd(path)
                    }
                }
            )
        )
    }

    // 目录被删掉之后：还有备份就留着（划线置灰提示「已不存在」），
    // 一个备份都没有就直接从列表消失 —— 和应用列表「只在备份里存在」的处理一致
    val visibleDirs = remember(dirs, summaries, refreshTick, query, reverse) {
        val keyword = query.trim()
        val filtered = dirs.filter { path ->
            val exists = File(path).isDirectory
            if (!exists && summaries[path] == null) return@filter false
            if (keyword.isEmpty()) return@filter true
            // 完整路径或文件夹名任一命中即可
            path.contains(keyword, ignoreCase = true) ||
                path.trimEnd('/').substringAfterLast('/').contains(keyword, ignoreCase = true)
        }
        // 先过滤再反转：搜索命中的那几条也要按倒序展示
        if (reverse) filtered.asReversed() else filtered
    }

    TargetListScreen(
        rows = visibleDirs.map { TargetRow(BackupTarget.DIR, dirAppInfo(it)) },
        summaries = summaries,
        listState = listState,
        onOpen = { onOpen(it.packageName) },
        modifier = modifier,
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        // 右下角圆形按钮：打开全屏文件夹选择弹窗
        extraAction = {
            FloatingActionButton(
                onClick = pickFolder,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            ) {
                Icon(Icons.Filled.CreateNewFolder, contentDescription = "添加文件夹")
            }
        }
    )
}
