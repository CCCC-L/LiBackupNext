package com.cc.libackupnext.ui.dialog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.window.DialogProperties
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
    // 新增不预填默认密码；编辑仍带出记录里保存的真实密码（不是默认值）
    var dialogPassword by remember(editing) {
        mutableStateOf(editing?.password.orEmpty())
    }
    // 确认栏每次打开都留空：新增和编辑都必须手动再输一次才解锁初始化 / 保存
    var dialogConfirmPassword by remember(editing) { mutableStateOf("") }
    var isInitializing by remember { mutableStateOf(false) }
    var initError by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    // 按钮置灰和 startInit 的防御共用这一个判定，别两处各写一遍
    val passwordsMatch = dialogPassword.isNotBlank() &&
            dialogPassword == dialogConfirmPassword

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
        if (!passwordsMatch) {
            AppLog.toastOnMain("两次输入的密码不一致")
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
                    initError = it.message ?: "未知错误"
                }
        }
    }

    /** 远程路径浏览的前置门禁 / 手动测试连接：成功 Toast、失败原因内联显示。 */
    fun runWhenConnected(action: () -> Unit = {}) {
        if (testing || !dialogRepo.isComplete()) return
        scope.launch {
            testing = true
            initError = null
            val result = RcloneClient.testRemoteConnection(dialogRepo)
            result
                .onSuccess { AppLog.toastOnMain("连接成功") }
                .onFailure { initError = it.message ?: "未知错误" }
            testing = false
            if (result.isSuccess) action()
        }
    }

    // 远程连接测试由业务层 initRemoteRepository 统一做，这里不再重复测连
    val submit: () -> Unit = { startInit() }
    // 远程路径没选过时按钮置灰：空路径会把整个服务根当成仓库
    val canSubmit = !isInitializing && !testing &&
            dialogRepo.canInit() && passwordsMatch

    // 表单 + 状态区共用一份固定预算：进度 / 红字按需出现时从预算里扣掉同样高度，
    // 平时 / 初始化中 / 失败三态等高；不按屏幕尺寸计算。
    val progressRowHeight = 26.dp
    val errorTextHeight = 72.dp
    val errorBlockHeight = errorTextHeight + 8.dp   // 上下 padding 各 4dp
    val formHeight = when {
        initError != null -> 420.dp - errorBlockHeight   // 340 + 80 = 420
        isInitializing -> 420.dp - progressRowHeight    // 394 + 26 = 420
        else -> 420.dp                                 // 无状态块：420
    }

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
                            // 非 weight 子项先测量：按钮行永远先拿到空间，键盘弹出也挤不掉
                            .weight(1f, fill = false)
                            // 固定高度（不是上限）：内容不足时预算才真的守恒
                            .heightIn(min = formHeight, max = formHeight)
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
                            confirmPassword = dialogConfirmPassword,
                            onConfirmPasswordChange = {
                                dialogConfirmPassword = it
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

                    // 进度按需出现、不常驻：固定高度已计入滚动区预算，弹窗不会被拉长
                    if (isInitializing) {
                        Row(
                            modifier = Modifier
                                .height(progressRowHeight)
                                .padding(horizontal = 24.dp),
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

                    // 失败原因和按钮同处底部固定区：自身限高滚动，长错误不会把按钮挤出屏幕
                    initError?.let { message ->
                        Column(
                            modifier = Modifier
                                .padding(horizontal = 24.dp, vertical = 4.dp)
                                .height(errorTextHeight)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
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
