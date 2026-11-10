package com.cc.libackupnext.ui.screen

import android.content.ClipData
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cc.libackupnext.BuildConfig
import com.cc.libackupnext.data.AppPrefs
import com.cc.libackupnext.service.RusticClient
import com.cc.libackupnext.ui.PageContentPadding
import com.cc.libackupnext.ui.dialog.PruneProgressDialog
import com.cc.libackupnext.ui.theme.dialogContainer
import com.cc.libackupnext.ui.util.AppIconStore
import com.cc.libackupnext.ui.util.formatSize
import com.cc.libackupnext.util.AppLog
import com.cc.libackupnext.util.KeepAwake
import com.cc.libackupnext.util.LogReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页的内容：Rustic 仓库信息 + 备份行为。
 *
 * 仓库地址只读展示（灰色，远程显示真实服务地址而非 remote:<id>: 逻辑标识），
 * 点击进入「已添加仓库」列表页，长按复制当前地址；
 * 配置 / 编辑仓库的弹窗由列表页持有，这里不再弹窗。
 *
 * 它是底栏的一个 tab，顶栏 / 底栏由主页提供，所以这里只有内容没有 Scaffold。
 */
@Composable
fun SettingsContent(
    modifier: Modifier = Modifier,
    /** 当前仓库地址（展示用）：外层按仓库变化重新读 AppPrefs 后传进来，这里只展示。 */
    repoAddress: String,
    /** 影响应用列表的设置项改动后通知外面重新加载。 */
    onSettingChanged: () -> Unit = {},
    /** 打开已添加仓库列表页。 */
    onOpenRepoList: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    // 整理仓库（prune）
    var showCleanConfirm by remember { mutableStateOf(false) }
    var cleaning by remember { mutableStateOf(false) }
    // 可回收空间：null 表示还没算出来
    var reclaimable by remember { mutableStateOf<Long?>(null) }
    var estimating by remember { mutableStateOf(false) }

    // 打开确认弹窗，同时在后台算一下能回收多少（只算不删）
    fun openCleanConfirm() {
        if (cleaning) return
        showCleanConfirm = true
        estimating = true
        reclaimable = null
        scope.launch {
            reclaimable = RusticClient.estimatePrune().getOrNull()
            estimating = false
        }
    }
    var backupSelf by remember { mutableStateOf(AppPrefs.backupSelf) }
    var fetchIconFromRepo by remember { mutableStateOf(AppPrefs.fetchIconFromRepo) }
    var checkUpdateOnLaunch by remember { mutableStateOf(AppPrefs.checkUpdateOnLaunch) }
    var verboseLog by remember { mutableStateOf(AppPrefs.verboseLog) }
    var exporting by remember { mutableStateOf(false) }

    // 系统文件选择器：用户自己选目录和文件名，不需要任何权限
    val exportLogs = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(LogReport.MIME_TYPE)
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        exporting = true
        scope.launch(Dispatchers.IO) {
            val result = LogReport.writeTo(context, uri)
            withContext(Dispatchers.Main) {
                exporting = false
                AppLog.toastOnMain(if (result.isSuccess) "日志已导出" else
                        "导出失败：${result.exceptionOrNull()?.message ?: "未知错误"}")
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PageContentPadding)
    ) {
        SectionTitle("Rustic")

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column {
                // rustic 依赖版本：只展示，不可点击（值来自 Cargo.toml）
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Text(
                        text = "Rustic 版本",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "core ${BuildConfig.RUSTIC_CORE_VERSION}" +
                                "  ·  backend ${BuildConfig.RUSTIC_BACKEND_VERSION}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = onOpenRepoList,
                            onLongClick = {
                                if (repoAddress.isNotBlank()) {
                                    val path = repoAddress
                                    scope.launch {
                                        clipboard.setClipEntry(
                                            ClipEntry(ClipData.newPlainText("仓库地址", path))
                                        )
                                        AppLog.toastOnMain("已复制仓库地址")
                                    }
                                }
                            }
                        )
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Text(
                        text = "仓库地址",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = repoAddress.ifBlank { "未设置" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "查看 / 管理已添加的仓库，长按复制当前地址",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                )

                // 整理仓库：红色，放在仓库地址下面
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !cleaning) { openCleanConfirm() }
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Text(
                        text = "整理仓库",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "清理无用的数据（已删除备份占用的空间）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.75f)
                    )
                }
            }
        }

        SectionTitle("备份")

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "备份自身",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "备份时将 LiBackupNext 的安装包复制到 rustic 仓库的根目录",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = backupSelf,
                    onCheckedChange = {
                        backupSelf = it
                        AppPrefs.backupSelf = it
                    }
                )
            }
        }

        AppearanceSection()

        SectionTitle("日志")

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !exporting) {
                            exportLogs.launch(LogReport.suggestedFileName())
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (exporting) "正在导出日志…" else "导出日志",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "用系统文件选择器保存到指定位置，含设备 / 系统 / 版本信息、操作索引与崩溃报告",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "详细日志",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "打开后 DEBUG 级日志也写入文件，复现问题时再开",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = verboseLog,
                        onCheckedChange = {
                            verboseLog = it
                            AppPrefs.verboseLog = it
                            AppLog.setVerbose(it)
                        }
                    )
                }
            }
        }

        SectionTitle("其他")

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "从仓库中获取图标",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "从仓库中获取没有安装应用的图标\n推荐关闭",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = fetchIconFromRepo,
                        onCheckedChange = {
                            fetchIconFromRepo = it
                            AppPrefs.fetchIconFromRepo = it
                            // 关掉时把已经取到的图标也清掉，列表重新加载
                            AppIconStore.clearAll()
                            onSettingChanged()
                        }
                    )
                }

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "启动时检查更新",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "当前版本 ${BuildConfig.APP_VERSION}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    Switch(
                        checked = checkUpdateOnLaunch,
                        onCheckedChange = {
                            checkUpdateOnLaunch = it
                            AppPrefs.checkUpdateOnLaunch = it
                        }
                    )
                }
            }
        }
    }

    if (showCleanConfirm) {
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.dialogContainer,
            onDismissRequest = { showCleanConfirm = false },
            title = { Text("整理仓库") },
            text = {
                Column {
                    Text("将清理已删除备份占用的磁盘空间，并回收没有备份的应用残留的图标。过程中请不要关闭应用。")

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = when {
                            estimating -> "正在估算可清理的空间…"
                            reclaimable == null -> "暂时无法估算可清理的空间"
                            reclaimable!! <= 0L -> "当前没有可清理的空间"
                            else -> "预计可清理 ${formatSize(reclaimable!!)}"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (reclaimable != null && reclaimable!! > 0L) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCleanConfirm = false
                        cleaning = true
                        scope.launch {
                            KeepAwake.acquire(context)
                            val result = try {
                                // 彻底清理：repack 所有半空的包
                                RusticClient.prune()
                            } finally {
                                KeepAwake.release()
                            }
                            cleaning = false
                            val removedIcons = result.getOrDefault(0)
                            AppLog.toastOnMain(when {
                                    result.isFailure ->
                                        "整理失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
                                    removedIcons > 0 -> "整理完成，清理了 $removedIcons 个残留图标"
                                    else -> "整理完成"
                                })
                        }
                    }
                ) {
                    Text("开始整理")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCleanConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }

    if (cleaning) {
        PruneProgressDialog()
    }

}

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 6.dp)
    )
}
