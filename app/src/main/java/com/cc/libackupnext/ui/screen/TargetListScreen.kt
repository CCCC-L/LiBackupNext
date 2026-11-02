package com.cc.libackupnext.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cc.libackupnext.data.AppBackupSummary
import com.cc.libackupnext.data.BackupTarget
import com.cc.libackupnext.entity.AppInfo
import com.cc.libackupnext.ui.util.AppItemCard
import com.cc.libackupnext.ui.util.AppListContainer
import com.cc.libackupnext.ui.util.isMissing
import com.cc.libackupnext.ui.util.targetViewOf

/**
 * 列表里的一行：属于哪个目标 + 展示数据。
 *
 * 一页可以列多个目标（以后的「其他」页就是 Wi-Fi / 通话记录 / 联系人各一行），
 * 所以目标跟着行走，而不是整页共用一个。
 */
data class TargetRow(val target: BackupTarget, val item: AppInfo)

/**
 * 备份目标列表：应用页 / 文件页 / 其他页共用。
 *
 * 图标、标题、副标题、要不要置灰都由 [com.cc.libackupnext.ui.util.TargetView] 决定，
 * 这里只负责排版、备份数、下拉刷新和点击。
 *
 * @param extraBottomText 第三行下面的小字（应用页按备份时间排序时显示时间）
 * @param extraAction 右下角浮着的按钮（文件页的「添加文件夹」），不需要就传 null
 * @param onRefresh 下拉刷新的动作，由调用方给：应用页重拉应用列表，文件页重算目录 + 备份数。
 *   传 null 表示这一页不支持下拉
 * @param isRefreshing 刷新转圈的状态，同样由调用方维护
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TargetListScreen(
    rows: List<TargetRow>,
    summaries: Map<String, AppBackupSummary>,
    listState: LazyListState,
    onOpen: (AppInfo) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(
        start = 16.dp,
        end = 16.dp,
        top = 8.dp,
        bottom = 96.dp
    ),
    extraBottomText: (AppInfo) -> String? = { null },
    extraAction: (@Composable () -> Unit)? = null,
    isRefreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
) {
    Box(modifier = modifier.fillMaxSize()) {
        if (onRefresh == null) {
            ListContent(rows, summaries, listState, onOpen, contentPadding, extraBottomText)
        } else {
            // 下拉刷新：手势只作用于列表，刷新时不显示全屏 loading
            val pullState = rememberPullToRefreshState()
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                state = pullState,
                modifier = Modifier.fillMaxSize(),
                indicator = {
                    PullToRefreshDefaults.LoadingIndicator(
                        state = pullState,
                        isRefreshing = isRefreshing,
                        modifier = Modifier.align(Alignment.TopCenter)
                    )
                }
            ) {
                ListContent(rows, summaries, listState, onOpen, contentPadding, extraBottomText)
            }
        }

        if (extraAction != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 96.dp)
            ) {
                extraAction()
            }
        }
    }
}

@Composable
private fun ListContent(
    rows: List<TargetRow>,
    summaries: Map<String, AppBackupSummary>,
    listState: LazyListState,
    onOpen: (AppInfo) -> Unit,
    contentPadding: PaddingValues,
    extraBottomText: (AppInfo) -> String?,
) {
    AppListContainer(
        items = rows,
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        // 一页可能有多个目标，key 要带上目标，否则不同目标下同名的条目会撞
        key = { _, row -> "${row.target.name}:${row.item.packageName}" }
    ) { _, row, shape ->
        val view = targetViewOf(row.target, row.item, row.target.isMissing(row.item))

        AppItemCard(
            icon = null,
            iconContent = { view.Icon(Modifier.size(48.dp)) },
            frozen = view.isFrozen,
            dimmed = view.isMissing,
            title = view.title,
            subtitle = view.subtitle,
            bottomText = "${summaries[row.item.packageName]?.count ?: 0}个备份",
            extraBottomText = extraBottomText(row.item),
            shape = shape,
            trailingContent = view.trailing(),
            onClick = { onOpen(row.item) }
        )
    }
}
