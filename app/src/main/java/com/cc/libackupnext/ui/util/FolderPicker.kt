package com.cc.libackupnext.ui.util

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

/**
 * 系统文件夹选择器（SAF `OpenDocumentTree`）的统一入口。
 *
 * @return 可直接当 `onClick` / `onPickFolder` 用的函数。
 */
@Composable
fun rememberFolderPicker(onPicked: (Uri) -> Unit): () -> Unit {
    val context = LocalContext.current
    // 回调可能跨重组变化，保证 ActivityResult 回调里拿到的是最新那份
    val currentOnPicked = rememberUpdatedState(onPicked)

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult

        // 某些 provider 不支持持久化权限，失败忽略即可
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        currentOnPicked.value(uri)
    }

    return remember(launcher) { { launcher.launch(null) } }
}
