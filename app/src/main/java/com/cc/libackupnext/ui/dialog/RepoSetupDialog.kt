package com.cc.libackupnext.ui.dialog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.cc.libackupnext.data.AppPrefs
import com.cc.libackupnext.data.FolderPickerLauncher
import com.cc.libackupnext.data.FolderPickerMode
import com.cc.libackupnext.data.RemoteRepo
import com.cc.libackupnext.data.SavedRepo
import com.cc.libackupnext.service.RcloneClient
import com.cc.libackupnext.service.RusticClient
import com.cc.libackupnext.ui.theme.dialogContainer
import com.cc.libackupnext.ui.util.RepoSetupForm
import com.cc.libackupnext.util.AppLog
import kotlinx.coroutines.launch

/**
 * 配置 / 编辑 Rustic 仓库的弹窗：新增和编辑共用一份表单。
 *
 * 底部按钮行自己画（[RepoSetupForm] 的 showActions 关掉）：编辑时「删除」
 * 固定在最左边、红色，复用 rememberDoubleClickGuard 双击才执行；右侧是取消、
 * 测试连接（远程）和无背景的「初始化」。测连接结果用 Toast 提示。
 * 远程路径没选过时初始化按钮置灰。编辑保存仍走 initRepo 候选校验，通过才落盘；
 * 当前仓库仍是灰色删除按钮，但可点击，点了用 Toast 说明「先切换到其它仓库」。
 * 初始化中和失败时弹窗都不关闭，进度 / 错误显示在弹窗内，
 * 只有成功才由 [onFinished] 关掉。
 *
 * 本地 / 远程目录浏览要先收起本弹窗，避免两层 Dialog 叠出两层 scrim；
 * 文件夹弹窗关闭时 onClose 把 [visible] 置回 true，表单数据不会丢。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RepoSetupDialog(
    /** null = 新增仓库；非 null = 编辑这条记录（表单预填它的数据）。 */
    editing: SavedRepo?,
    onDismiss: () -> Unit,
    onOpenFolderPicker: (FolderPickerLauncher) -> Unit,
    /** 新增 / 编辑 / 删除结束后统一回调：外部关弹窗并刷新清单。 */
    onFinished: () -> Unit
) {
    val scope = rememberCoroutineScope()
    // 复用批量页 / 详情页那套双击守卫：第一次点只提示，300ms 内再点才真删
    val deleteGuard = rememberDoubleClickGuard("双击删除仓库")

    val isCurrent = editing != null && editing.location == AppPrefs.repoPath
    var visible by remember { mutableStateOf(true) }
    var dialogRepo by remember(editing) {
        mutableStateOf(editing?.toForm() ?: RemoteRepo())
    }
    var dialogPassword by remember(editing) {
        mutableStateOf(editing?.password?.ifBlank { "rustic" } ?: "rustic")
    }
    var isInitializing by remember { mutableStateOf(false) }
    var initError by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    // 本地目录浏览：先收起本弹窗，关闭时再恢复
    val pickFolder: () -> Unit = {
        visible = false
        onOpenFolderPicker(
            FolderPickerLauncher(
                mode = FolderPickerMode.LOCAL,
                initialPath = dialogRepo.path,
                onPick = { picked -> dialogRepo = dialogRepo.copy(path = picked) },
                onClose = { visible = true }
            )
        )
    }

    // 远程路径浏览：测连接成功后才打开全屏文件夹弹窗
    val browseRemotePath: () -> Unit = {
        visible = false
        onOpenFolderPicker(
            FolderPickerLauncher(
                mode = FolderPickerMode.REMOTE,
                repo = dialogRepo,
                initialPath = dialogRepo.path,
                onPick = { picked -> dialogRepo = dialogRepo.copy(path = picked) },
                onClose = { visible = true }
            )
        )
    }

    /** 新增、编辑都在这里落地：候选值先校验，成功才写 AppPrefs。 */
    fun startInit() {
        if (isInitializing) return
        if (!dialogRepo.isComplete()) {
            AppLog.toastOnMain(if (dialogRepo.protocol.isLocal) "请先选择仓库目录" else "请先填完远程服务配置")
            return
        }
        // 远程路径必须真的选过：空路径会把整个服务根当成仓库
        if (!dialogRepo.protocol.isLocal && dialogRepo.path.isBlank()) {
            AppLog.toastOnMain("请先选择远程路径")
            return
        }
        if (dialogPassword.isBlank()) {
            AppLog.toastOnMain("请先输入仓库密码")
            return
        }

        scope.launch {
            isInitializing = true
            initError = null
            val result = when {
                editing != null ->
                    RusticClient.updateSavedRepo(editing, dialogRepo, dialogPassword)

                dialogRepo.protocol.isLocal ->
                    RusticClient.initRepository(dialogRepo.path, dialogPassword)

                else -> RusticClient.initRemoteRepository(dialogRepo, dialogPassword)
            }
            // 先收进度弹窗再回调：onFinished() 会把整个弹窗移出组合
            isInitializing = false
            result
                .onSuccess {
                    AppLog.toastOnMain(if (editing != null) "仓库已保存" else "仓库初始化成功")
                    onFinished()
                }
                .onFailure {
                    AppLog.e("仓库", if (editing != null) "保存仓库失败" else "初始化失败", it)
                    val message = it.message ?: "未知错误"
                    initError = message
                    AppLog.toastOnMain(message, longToast = true)
                }
        }
    }

    /** 远程路径浏览的前置门禁 / 手动测试连接：测连接成功才执行 action，结果用 Toast 提示。 */
    fun runWhenConnected(action: () -> Unit = {}) {
        if (testing || !dialogRepo.isComplete()) return
        scope.launch {
            testing = true
            val result = RcloneClient.testRemoteConnection(dialogRepo)
            AppLog.toastOnMain(result.fold(
                    onSuccess = { "连接成功" },
                    onFailure = { "连接失败：${it.message ?: "未知错误"}" }
                ))
            testing = false
            if (result.isSuccess) action()
        }
    }

    // 远程连接测试由业务层 initRemoteRepository 统一做，这里不再重复测连
    val submit: () -> Unit = { startInit() }
    // 远程路径没选过时按钮置灰：空路径会把整个服务根当成仓库
    val canSubmit = !isInitializing && !testing &&
            dialogRepo.canInit() && dialogPassword.isNotBlank()

    // 初始化中和失败时都保持弹窗，只有成功（onFinished）才关闭；
    // 删除改成双击执行，没有确认弹窗，表单也就不用收起
    val showForm = visible

    if (showForm) {
        Dialog(
            onDismissRequest = { if (!isInitializing) onDismiss() },
            properties = DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = false
            )
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.dialogContainer
            ) {
                Column(Modifier.padding(vertical = 20.dp)) {
                    Text(
                        text = if (editing != null) "编辑仓库" else "配置 Rustic 仓库",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp, vertical = 12.dp)
                    ) {
                        RepoSetupForm(
                            repo = dialogRepo,
                            onRepoChange = {
                                dialogRepo = it
                                initError = null
                            },
                            password = dialogPassword,
                            onPasswordChange = {
                                dialogPassword = it
                                initError = null
                            },
                            enabled = !isInitializing,
                            onPickFolder = pickFolder,
                            onBrowseRemotePath = browseRemotePath,
                            onConnectedAction = { action -> runWhenConnected(action) },
                            isTesting = testing,
                            onInit = { startInit() },
                            showActions = false,
                            pathTextStyle = MaterialTheme.typography.bodyMedium
                        )
                    }

                    // 初始化中不收起弹窗：进度和失败原因都留在弹窗里，只有成功才关闭
                    if (isInitializing) {
                        Row(
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            LoadingIndicator(modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "正在初始化仓库…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    initError?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                        )
                    }

                    // 底部按钮行：编辑时删除固定在最左边
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (editing != null) {
                            TextButton(
                                onClick = {
                                    if (isCurrent) {
                                        AppLog.toastOnMain("当前仓库不可删除，请先切换到其它仓库")
                                    } else if (deleteGuard.consume("delete-repo")) {
                                        // 双击才真删：第一次只弹提示，300ms 内第二次才移除记录
                                        AppPrefs.removeSavedRepo(editing.location)
                                        AppLog.toastOnMain("已移除仓库记录（备份数据未动）")
                                        onFinished()
                                    }
                                },
                                // 当前仓库保持灰色外观但可点击，点了用 Toast 说明原因
                                enabled = !isInitializing,
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = if (isCurrent) {
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    }
                                )
                            ) { Text("删除") }
                        }
                        Spacer(Modifier.weight(1f))
                        if (!dialogRepo.protocol.isLocal) {
                            TextButton(
                                onClick = { runWhenConnected {} },
                                enabled = !isInitializing && !testing && dialogRepo.isComplete()
                            ) { Text(if (testing) "测试中…" else "测试连接") }
                        }
                        TextButton(
                            onClick = onDismiss,
                            enabled = !isInitializing
                        ) { Text("取消") }
                        // 无背景：和取消 / 测试连接同一排文字按钮，路径没选完时置灰
                        TextButton(onClick = submit, enabled = canSubmit) {
                            Text("初始化")
                        }
                    }
                }
            }
        }
    }
}
