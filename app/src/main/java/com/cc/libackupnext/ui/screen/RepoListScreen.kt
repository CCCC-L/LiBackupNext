package com.cc.libackupnext.ui.screen

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.cc.libackupnext.data.FolderPickerLauncher
import com.cc.libackupnext.data.SavedRepo
import com.cc.libackupnext.service.RusticClient
import com.cc.libackupnext.ui.AppTopBar
import com.cc.libackupnext.ui.dialog.RepoSetupDialog
import com.cc.libackupnext.ui.theme.dialogContainer
import com.cc.libackupnext.ui.util.AppItemCard
import com.cc.libackupnext.ui.util.AppListContainer
import kotlinx.coroutines.launch
import com.cc.libackupnext.util.AppLog

/** null = 不显示弹窗；editing=null 是新增，非 null 是编辑这条记录。 */
private data class RepoDialogTarget(val editing: SavedRepo?)

/**
 * 已添加仓库的列表页：设置页「仓库地址」点进来。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RepoListScreen(
    repos: List<SavedRepo>,
    currentLocation: String,
    onBack: () -> Unit,
    onOpenFolderPicker: (FolderPickerLauncher) -> Unit,
    /** 新增 / 编辑 / 删除 / 切换后通知外层刷新清单、设置页地址和备份列表。 */
    onRepoChanged: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val pageBackground = MaterialTheme.colorScheme.surfaceContainer
    val searchBarBackground = MaterialTheme.colorScheme.surfaceContainerHighest

    var dialogTarget by remember { mutableStateOf<RepoDialogTarget?>(null) }
    var switching by remember { mutableStateOf(false) }
    var switchError by remember { mutableStateOf<String?>(null) }

    /** 切换也走初始化入口：远程先测连接，校验失败不改当前仓库。 */
    fun switchTo(record: SavedRepo) {
        if (switching || record.location == currentLocation) return
        scope.launch {
            switching = true
            val result = record.remote?.let {
                RusticClient.initRemoteRepository(it, record.password, switching = true)
            } ?: RusticClient.initRepository(
                record.location, record.password, switching = true
            )
            switching = false
            result
                .onSuccess {
                    AppLog.toastOnMain("已切换到 ${record.name.ifBlank { record.address }}")
                    onRepoChanged()
                }
                .onFailure {
                    switchError = it.message ?: "未知错误"
                }
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = pageBackground,
        topBar = {
            AppTopBar(
                viewModel = null,
                title = "仓库",
                pageBackground = pageBackground,
                searchBarBackground = searchBarBackground,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                showSearch = false,
                showSortMenu = false,
                showMiddleButton = false,
                showUserButton = false,
                customActions = {
                    IconButton(onClick = { dialogTarget = RepoDialogTarget(null) }) {
                        Icon(Icons.Filled.Add, contentDescription = "新建仓库")
                    }
                }
            )
        }
    ) { padding ->
        AppListContainer(
            items = repos,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            key = { _, record -> record.location }
        ) { _, record, shape ->
            RepoCard(
                record = record,
                isCurrent = record.location == currentLocation,
                switching = switching,
                shape = shape,
                onSwitch = { switchTo(record) },
                onEdit = { dialogTarget = RepoDialogTarget(record) }
            )
        }
    }

    dialogTarget?.let { target ->
        RepoSetupDialog(
            editing = target.editing,
            onDismiss = { dialogTarget = null },
            onOpenFolderPicker = onOpenFolderPicker,
            onFinished = {
                dialogTarget = null
                onRepoChanged()
            }
        )
    }

    if (switching) {
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.dialogContainer,
            onDismissRequest = { },
            title = { Text("切换仓库") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LoadingIndicator(modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("正在校验仓库…")
                }
            },
            confirmButton = { }
        )
    }

    switchError?.let { message ->
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.dialogContainer,
            onDismissRequest = { switchError = null },
            title = { Text("切换失败") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { switchError = null }) { Text("确定") }
            }
        )
    }
}

@Composable
private fun RepoCard(
    record: SavedRepo,
    isCurrent: Boolean,
    switching: Boolean,
    shape: Shape,
    onSwitch: () -> Unit,
    onEdit: () -> Unit
) {
    AppItemCard(
        title = record.name.ifBlank { record.address },
        subtitle = record.address,
        bottomText = record.remote?.protocol?.label?.let { "远程 · $it" } ?: "本地仓库",
        shape = shape,
        onClick = if (!isCurrent && !switching) onSwitch else null,
        endPadding = 8.dp,
        titleTrailing = {
            if (isCurrent) {
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = "当前使用中",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
        },
        trailingContent = {
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = "编辑仓库")
            }
        }
    )
}
