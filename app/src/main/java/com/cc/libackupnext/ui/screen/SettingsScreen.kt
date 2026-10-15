package com.cc.libackupnext.ui.screen

import android.content.ClipData
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cc.libackupnext.BuildConfig
import com.cc.libackupnext.data.AppPrefs
import com.cc.libackupnext.service.RusticService
import com.cc.libackupnext.ui.dialog.PruneProgressDialog
import com.cc.libackupnext.ui.util.RepoSetupForm
import com.cc.libackupnext.ui.util.formatSize
import com.cc.libackupnext.ui.util.AppIconStore
import com.cc.libackupnext.util.ErrorLog
import com.cc.libackupnext.util.KeepAwake
import com.cc.libackupnext.util.RepoPath
import kotlinx.coroutines.launch

/**
 * 设置页：Rustic 仓库信息 + 备份行为。
 *
 * 仓库地址只读展示（灰色），点击弹确认框后重新选目录并初始化仓库，长按复制当前路径。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    /** 影响应用列表的设置项改动后通知外面重新加载。 */
    onSettingChanged: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current

    var repoPath by remember { mutableStateOf(AppPrefs.repoPath) }
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
            reclaimable = RusticService.estimatePrune().getOrNull()
            estimating = false
        }
    }
    var backupSelf by remember { mutableStateOf(AppPrefs.backupSelf) }
    var fetchIconFromRepo by remember { mutableStateOf(AppPrefs.fetchIconFromRepo) }

    // 选目录 + 初始化仓库的弹窗状态
    var showDialog by remember { mutableStateOf(false) }
    var dialogPath by remember { mutableStateOf("") }
    var dialogPassword by remember { mutableStateOf("") }
    var isInitializing by remember { mutableStateOf(false) }
    var initError by remember { mutableStateOf<String?>(null) }

    // 和欢迎页一样：选完目录只回填路径，密码和初始化在弹窗里完成
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val path = RepoPath.toFilesystemPath(uri.toString())
        if (path == null) {
            Toast.makeText(context, "无法解析该目录", Toast.LENGTH_SHORT).show()
        } else {
            dialogPath = path
        }
    }

    fun startInit() {
        if (isInitializing) return
        if (dialogPath.isBlank()) {
            Toast.makeText(context, "请先选择仓库目录", Toast.LENGTH_SHORT).show()
            return
        }
        if (dialogPassword.isBlank()) {
            Toast.makeText(context, "请先输入仓库密码", Toast.LENGTH_SHORT).show()
            return
        }

        scope.launch {
            isInitializing = true
            initError = null
            RusticService.initRepository(dialogPath, dialogPassword)
                .onSuccess {
                    repoPath = dialogPath
                    showDialog = false
                    Toast.makeText(context, "仓库初始化成功", Toast.LENGTH_SHORT).show()
                    // 换仓库了：让列表重新读一次，别留着旧仓库的备份数
                    onSettingChanged()
                }
                .onFailure {
                    ErrorLog.add("仓库", "初始化失败", it)
                    initError = it.message ?: "未知错误"
                }
            isInitializing = false
        }
    }

    val isDark = isSystemInDarkTheme()
    val pageBackground =
        if (isDark) MaterialTheme.colorScheme.surfaceContainerLow else Color(0xFFEAEEF7)

    Scaffold(
        containerColor = pageBackground,
        topBar = {
            // 设置页不需要搜索 / 用户 / 排序 / 更多，只留标题和返回
            TopAppBar(
                title = {
                    Text(
                        text = "设置",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = pageBackground)
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            SectionTitle("Rustic")

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
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
                                onClick = {
                                    dialogPath = repoPath
                                    dialogPassword = AppPrefs.repoPassword.ifBlank { "rustic" }
                                    showDialog = true
                                },
                                onLongClick = {
                                    if (repoPath.isNotBlank()) {
                                        val path = repoPath
                                        scope.launch {
                                            clipboard.setClipEntry(
                                                ClipEntry(ClipData.newPlainText("仓库地址", path))
                                            )
                                            Toast.makeText(
                                                context,
                                                "已复制仓库地址",
                                                Toast.LENGTH_SHORT
                                            ).show()
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
                            text = repoPath.ifBlank { "未设置" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "点击选择并初始化仓库，长按复制地址",
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
                shape = RoundedCornerShape(20.dp),
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

            SectionTitle("其他")

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
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
            }
        }
    }

    if (showCleanConfirm) {
        AlertDialog(
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
                                RusticService.prune()
                            } finally {
                                KeepAwake.release()
                            }
                            cleaning = false
                            val removedIcons = result.getOrDefault(0)
                            Toast.makeText(
                                context,
                                when {
                                    result.isFailure ->
                                        "整理失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
                                    removedIcons > 0 -> "整理完成，清理了 $removedIcons 个残留图标"
                                    else -> "整理完成"
                                },
                                Toast.LENGTH_SHORT
                            ).show()
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

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { if (!isInitializing) showDialog = false },
            title = { Text("选择并初始化仓库") },
            text = {
                // 和引导页共用同一份实现（目录 + 密码 + 「请牢记密码」提示）
                RepoSetupForm(
                    path = dialogPath,
                    onPickFolder = { folderPicker.launch(null) },
                    password = dialogPassword,
                    onPasswordChange = { dialogPassword = it },
                    enabled = !isInitializing,
                    pathTextStyle = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { startInit() },
                    enabled = !isInitializing &&
                            dialogPath.isNotBlank() &&
                            dialogPassword.isNotBlank(),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("初始化")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDialog = false },
                    enabled = !isInitializing
                ) {
                    Text("取消")
                }
            }
        )
    }

    if (isInitializing) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("初始化仓库") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("正在初始化仓库…")
                }
            },
            confirmButton = { }
        )
    }

    initError?.let { message ->
        AlertDialog(
            onDismissRequest = { initError = null },
            title = { Text("初始化失败") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { initError = null }) {
                    Text("确定")
                }
            }
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 6.dp)
    )
}
