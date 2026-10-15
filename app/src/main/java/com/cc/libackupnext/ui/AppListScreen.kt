package com.cc.libackupnext.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cc.libackupnext.entity.AppInfo

@Composable
fun AppListScreen(viewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val appName = remember { context.applicationInfo.loadLabel(context.packageManager).toString() }

    val displayApps by viewModel.displayApps.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()

    // 颜色动态适配
    val isDark = isSystemInDarkTheme()
    val pageBackground =
        if (isDark) MaterialTheme.colorScheme.surfaceContainerLow else Color(0xFFEAEEF7)
    val searchBarBackground =
        if (isDark) MaterialTheme.colorScheme.surfaceContainerHighest else Color(0xFFE2E7F1)

    BackHandler(enabled = isSearching) {
        viewModel.toggleSearch()
    }

    Scaffold(
        containerColor = pageBackground,
        topBar = {
            AppTopBar(
                viewModel = viewModel,
                appName = appName,
                pageBackground = pageBackground,
                searchBarBackground = searchBarBackground
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) {
            itemsIndexed(displayApps, key = { _, app -> app.packageName }) { index, app ->
                val isFirst = index == 0
                val isLast = index == displayApps.lastIndex

                val shape = when {
                    isFirst && isLast -> RoundedCornerShape(24.dp)
                    isFirst -> RoundedCornerShape(
                        topStart = 24.dp, topEnd = 24.dp,
                        bottomStart = 6.dp, bottomEnd = 6.dp
                    )
                    isLast -> RoundedCornerShape(
                        topStart = 6.dp, topEnd = 6.dp,
                        bottomStart = 24.dp, bottomEnd = 24.dp
                    )
                    else -> RoundedCornerShape(6.dp)
                }

                AppItemCard(app = app, shape = shape)
            }
        }
    }
}

@Composable
fun AppItemCard(app: AppInfo, shape: Shape) {
    // 用 Surface 替代 ElevatedCard，去掉阴影（即"边框"），保留圆角与背景色
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val bitmap = remember(app.icon) { app.icon.toBitmap(96, 96).asImageBitmap() }
            Image(
                bitmap = bitmap,
                contentDescription = app.name,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
            )

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${app.version} (${app.versionCode})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}