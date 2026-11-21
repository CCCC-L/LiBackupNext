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
import com.cc.libackupnext.data.AppInfo
import com.cc.libackupnext.data.BackupTarget
import com.cc.libackupnext.data.FolderPickerLauncher
import com.cc.libackupnext.data.FolderPickerMode
import com.cc.libackupnext.util.AppLog

/**
 * 文件页：列出要备份的目录。
 *
 * 行由 [com.cc.libackupnext.data.dirRows] 算好（备份目标 ∪ 仓库里有备份的目录），
 * 这里只负责搜索、倒序和画。点一行进这个目录的「文件信息」页，快照 label 是 `<绝对路径>:dir`。
 */
@Composable
fun FileListScreen(
    /** 已经算好的行：带 `onlyInBackup`（已移除备份目标），目录在不在由渲染时判 */
    dirs: List<AppInfo>,
    summaries: Map<String, AppBackupSummary>,
    /** 每次从后台回到前台递增：让行重新算一遍「目录还在不在」（划线判据在渲染时算） */
    refreshTick: Int,
    /** 搜索词：按完整路径或文件夹名匹配，空串不过滤 */
    query: String,
    listState: LazyListState,
    onOpen: (AppInfo) -> Unit,
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
                    val row = dirs.firstOrNull { it.packageName == path }
                    // 只剩备份的行（已移除备份目标）再选一次 = 重新加回目标
                    if (row != null && !row.onlyInBackup) {
                        AppLog.toastOnMain("已经在列表里了")
                    } else {
                        onAdd(path)
                    }
                }
            )
        )
    }

    // 列表就是并集，不再按「磁盘上还在不在」筛行：目录被删了也要露出来（划线置灰），
    // 否则它一直留在备份目标里，界面上却够不着。
    // refreshTick 进 key 是为了让行重新算一次存在性 —— 划线判据在渲染时才读磁盘。
    val visible = remember(dirs, refreshTick, query, reverse) {
        val keyword = query.trim()
        val filtered = if (keyword.isEmpty()) {
            dirs
        } else {
            // 完整路径或文件夹名任一命中即可
            dirs.filter {
                it.packageName.contains(keyword, ignoreCase = true) ||
                        it.name.contains(keyword, ignoreCase = true)
            }
        }
        // 先过滤再反转：搜索命中的那几条也要按倒序展示
        if (reverse) filtered.asReversed() else filtered
    }

    TargetListScreen(
        rows = remember(visible) { visible.map { TargetRow(BackupTarget.DIR, it) } },
        summaries = summaries,
        listState = listState,
        onOpen = onOpen,
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
