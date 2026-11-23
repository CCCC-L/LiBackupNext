package com.cc.libackupnext.ui.screen

import android.content.ClipData
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cc.libackupnext.BuildConfig
import com.cc.libackupnext.service.display.screenOnSetting
import com.cc.libackupnext.ui.PageContentPadding
import com.cc.libackupnext.ui.component.AppDialog
import com.cc.libackupnext.ui.component.CardGroup
import com.cc.libackupnext.ui.component.SettingsCard
import com.cc.libackupnext.ui.di.LocalBackupPrefs
import com.cc.libackupnext.ui.di.LocalRepoWriter
import com.cc.libackupnext.ui.di.LocalViewPrefs
import com.cc.libackupnext.ui.dialog.PruneProgressDialog
import com.cc.libackupnext.ui.util.AppIconStore
import com.cc.libackupnext.ui.util.formatSize
import com.cc.libackupnext.util.AppLog
import com.cc.libackupnext.util.KeepAwake
import com.cc.libackupnext.util.LogReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 设置页的内容：Rustic 仓库信息 + 备份行为。
 *
 * 仓库地址只读展示（灰色，远程显示真实服务地址而非 remote:<id>: 逻辑标识），
 * 点击进入「已添加仓库」列表页，长按复制当前地址；
 * 配置 / 编辑仓库的弹窗由列表页持有，这里不再弹窗。
 *
 * 卡片用 [CardGroup] 分组，形状 / 行间距和 apptab 的列表卡片（AppListContainer）一致：
 * 组内只有 2dp 缝、首行上圆角、末行下圆角，没有分割线。
 *
 * 它是底栏的一个 tab，顶栏 / 底栏由主页提供，所以这里只有内容没有 Scaffold。
 */
@Composable
fun SettingsContent(
    modifier: Modifier = Modifier,
    /** 当前仓库地址（展示用）：外层按仓库变化重新读仓库信息后传进来，这里只展示。 */
    repoAddress: String,
    /** 影响应用列表的设置项改动后通知外面重新加载。 */
    onSettingChanged: () -> Unit = {},
    /** 打开已添加仓库列表页。 */
    onOpenRepoList: () -> Unit = {},
    /** 打开关于页。 */
    onOpenAbout: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    // 整理仓库走接口：设置页只需要写能力
    val writer = LocalRepoWriter.current
    val backupPrefs = LocalBackupPrefs.current
    val viewPrefs = LocalViewPrefs.current
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
            reclaimable = writer.estimatePrune().getOrNull()
            estimating = false
        }
    }

    var backupSelf by remember { mutableStateOf(backupPrefs.backupSelf) }
    var rescanMedia by remember { mutableStateOf(backupPrefs.rescanMediaAfterRestore) }
    val screenOnEnabled by screenOnSetting.enabled.collectAsState()
    var fetchIconFromRepo by remember { mutableStateOf(backupPrefs.fetchIconFromRepo) }
    var checkUpdateOnLaunch by remember { mutableStateOf(viewPrefs.checkUpdateOnLaunch) }
    var verboseLog by remember { mutableStateOf(viewPrefs.verboseLog) }
    var exporting by remember { mutableStateOf(false) }

    // 最大备份数：0 表示不限，滑块直接改
    var retentionLimit by remember { mutableStateOf(backupPrefs.retentionLimit) }

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
        CardGroup(
            "Rustic",
            // rustic 依赖版本：只展示，不可点击（值来自 Cargo.toml）
            { shape ->
                SettingsCard(
                    shape = shape,
                    title = "Rustic 版本",
                    subtitle = "core ${BuildConfig.RUSTIC_CORE_VERSION}" +
                            "  ·  backend ${BuildConfig.RUSTIC_BACKEND_VERSION}"
                )
            },
            { shape ->
                SettingsCard(
                    shape = shape,
                    title = "仓库地址",
                    subtitle = repoAddress.ifBlank { "未设置" },
                    subtitleMaxLines = 1,
                    hint = "查看 / 管理已添加的仓库，长按复制当前地址",
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
            },
            // 整理仓库：红色，放在仓库地址下面
            { shape ->
                SettingsCard(
                    shape = shape,
                    title = "整理仓库",
                    subtitle = "清理无用的数据（已删除备份占用的空间）",
                    titleColor = MaterialTheme.colorScheme.error,
                    subtitleColor = MaterialTheme.colorScheme.error.copy(alpha = 0.75f),
                    onClick = { if (!cleaning) openCleanConfirm() }
                )
            }
        )

        CardGroup(
            "备份",
            { shape ->
                SettingsCard(
                    shape = shape,
                    title = "备份自身",
                    subtitle = "备份时将 LiBackupNext 的安装包复制到 rustic 仓库的根目录",
                    trailing = {
                        Switch(
                            checked = backupSelf,
                            onCheckedChange = {
                                backupSelf = it
                                backupPrefs.backupSelf = it
                            }
                        )
                    }
                )
            },
            { shape ->
                SettingsCard(
                    shape = shape,
                    title = "备份/恢复时屏幕常亮",
                    subtitle = "操作期间保持屏幕点亮，结束后自动恢复",
                    trailing = {
                        Switch(
                            checked = screenOnEnabled,
                            onCheckedChange = { screenOnSetting.setEnabled(it) }
                        )
                    }
                )
            },
            { shape ->
                RetentionLimitCard(
                    shape = shape,
                    limit = retentionLimit,
                    onLimitChange = {
                        retentionLimit = it
                        backupPrefs.retentionLimit = it
                    }
                )
            }
        )

        CardGroup(
            "恢复",
            { shape ->
                SettingsCard(
                    shape = shape,
                    title = "文件夹恢复后重建媒体索引",
                    subtitle = "恢复文件夹后登记媒体文件，小米建议开启",
                    trailing = {
                        Switch(
                            checked = rescanMedia,
                            onCheckedChange = {
                                rescanMedia = it
                                backupPrefs.rescanMediaAfterRestore = it
                            }
                        )
                    }
                )
            }
        )

        AppearanceSection()

        CardGroup(
            "日志",
            { shape ->
                SettingsCard(
                    shape = shape,
                    title = if (exporting) "正在导出日志…" else "导出日志",
                    subtitle = "用系统文件选择器保存到指定位置，含设备 / 系统 / 版本信息、操作索引与崩溃报告",
                    onClick = {
                        if (!exporting) exportLogs.launch(LogReport.suggestedFileName())
                    }
                )
            },
            { shape ->
                SettingsCard(
                    shape = shape,
                    title = "详细日志",
                    subtitle = "打开后 DEBUG 级日志也写入文件，复现问题时再开",
                    trailing = {
                        Switch(
                            checked = verboseLog,
                            onCheckedChange = {
                                verboseLog = it
                                viewPrefs.verboseLog = it
                                AppLog.setVerbose(it)
                            }
                        )
                    }
                )
            }
        )

        CardGroup(
            "其他",
            { shape ->
                SettingsCard(
                    shape = shape,
                    title = "从仓库中获取图标",
                    subtitle = "从仓库中获取没有安装应用的图标",
                    hint = "推荐关闭",
                    trailing = {
                        Switch(
                            checked = fetchIconFromRepo,
                            onCheckedChange = {
                                fetchIconFromRepo = it
                                backupPrefs.fetchIconFromRepo = it
                                // 关掉时把已经取到的图标也清掉，列表重新加载
                                AppIconStore.clearAll()
                                onSettingChanged()
                            }
                        )
                    }
                )
            },
            { shape ->
                SettingsCard(
                    shape = shape,
                    title = "启动时检查更新",
                    subtitle = "启动时查询 GitHub 最新 Release",
                    trailing = {
                        Switch(
                            checked = checkUpdateOnLaunch,
                            onCheckedChange = {
                                checkUpdateOnLaunch = it
                                viewPrefs.checkUpdateOnLaunch = it
                            }
                        )
                    }
                )
            },
            { shape ->
                SettingsCard(
                    shape = shape,
                    title = "关于",
                    subtitle = "当前版本 ${BuildConfig.APP_VERSION} · 开发者与开源声明",
                    onClick = onOpenAbout
                )
            }
        )
    }

    if (showCleanConfirm) {
        AppDialog(
            title = "整理仓库",
            onDismiss = { showCleanConfirm = false },
            confirmText = "开始整理",
            onConfirm = {
                showCleanConfirm = false
                cleaning = true
                scope.launch {
                    KeepAwake.acquire(context)
                    val result = try {
                        // 彻底清理：repack 所有半空的包
                        writer.prune()
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
    }

    if (cleaning) {
        PruneProgressDialog()
    }

}

/**
 * 最大备份数：0~10 的滑块，`0` 表示不限。
 *
 * 用滑块而不是弹窗，是因为这个值只有一个维度、只有 11 个取值 ——
 * 点开弹窗再选一项再关闭，三步换一个数字，不如直接拖。
 * 卡片高度固定（副标题只写当前状态，不随值变长短），拖动时不会跳。
 */
@Composable
private fun RetentionLimitCard(
    shape: Shape,
    limit: Int,
    onLimitChange: (Int) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "最大备份数",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (limit <= 0) "不限" else "$limit 个",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Text(
                text = if (limit <= 0) {
                    "不自动删除，所有备份都留着"
                } else {
                    "超过 $limit 个备份后自动清理"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Slider(
                value = limit.toFloat(),
                onValueChange = { onLimitChange(it.roundToInt()) },
                valueRange = 0f..10f,
                // 0..10 共 11 个位置：中间 9 个刻度
                steps = 9,
                modifier = Modifier.fillMaxWidth()
            )


            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "只删被更新的备份完整覆盖的旧备份，所以实际可能多留几个",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}
