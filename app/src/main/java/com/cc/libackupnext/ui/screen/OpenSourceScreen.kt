package com.cc.libackupnext.ui.screen

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cc.libackupnext.data.openSourceLibs
import com.cc.libackupnext.ui.AppTopBar
import com.cc.libackupnext.ui.PageContentHorizontalPadding
import com.cc.libackupnext.ui.component.GroupVerticalSpacing
import com.cc.libackupnext.ui.component.SettingsCard
import com.cc.libackupnext.ui.component.cardGroup
import com.cc.libackupnext.util.AppLog

/**
 * 开源软件声明页：关于 → 开源软件声明。
 *
 * 和「仓库」页一样压在底栏之上；顶栏直接复用主 tab 页的 [AppTopBar]，列表用
 * [cardGroup] + [SettingsCard]，和设置页 / 关于页 / apptab 是同一套分组卡片。
 * 清单只来自 [openSourceLibs]，点击任一卡片打开对应上游 GitHub 仓库；
 * 这里不读写仓库、不碰凭据。
 */
@Composable
fun OpenSourceScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val pageBackground = MaterialTheme.colorScheme.surfaceContainer
    val searchBarBackground = MaterialTheme.colorScheme.surfaceContainerHighest

    // groupBy 保持清单里的声明顺序
    val groups = openSourceLibs.groupBy { it.group }

    fun openUrl(url: String) {
        val ok = runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
        if (!ok) AppLog.toastOnMain("没有可用的浏览器")
    }

    Scaffold(
        modifier = modifier,
        containerColor = pageBackground,
        topBar = {
            AppTopBar(
                viewModel = null,
                title = "开源软件声明",
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
                // 空动作区：跳过默认的「用户 / 排序 / 更多」按钮分支，避免带出空菜单
                customActions = {}
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                horizontal = PageContentHorizontalPadding,
                vertical = 12.dp
            ),
            verticalArrangement = Arrangement.spacedBy(GroupVerticalSpacing)
        ) {
            item {
                Text(
                    text = "本项目基于以下开源组件构建，点击条目可打开上游仓库；协议信息取自各上游仓库，以其官方声明为准。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
                )
            }

            groups.forEach { (group, libs) ->
                cardGroup(
                    title = group,
                    items = libs,
                    key = { lib -> "$group-${lib.name}" }
                ) { lib, shape ->
                    SettingsCard(
                        shape = shape,
                        title = lib.name,
                        subtitle = lib.license,
                        onClick = { openUrl(lib.url) }
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(20.dp)) }
        }
    }
}
