package com.cc.libackupnext.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.cc.libackupnext.data.BackupTarget
import kotlin.math.abs

/**
 * 主页面的页签。
 *
 * @param targets 这一页要列哪些备份目标。空 = 不是备份目标列表（设置页）。
 * @param searchHint / createLabel / manageLabel 顶栏文案；只有目标列表页用得到。
 */
enum class HomeTab(
    val label: String,
    val icon: ImageVector,
    val targets: List<BackupTarget>,
    val searchHint: String = "",
    val createLabel: String = "",
    val manageLabel: String = ""
) {
    APPS(
        "应用", Icons.Filled.Apps, listOf(BackupTarget.APP),
        searchHint = "搜索应用名称或包名",
        createLabel = "新建应用备份",
        manageLabel = "管理应用备份"
    ),

    FILES(
        "文件", Icons.Filled.Folder, listOf(BackupTarget.DIR),
        searchHint = "搜索文件夹路径或名称",
        createLabel = "新建文件备份",
        manageLabel = "管理文件备份"
    ),

    /** Wi-Fi / 联系人 / 通话记录：一页列多个固定目标。 */
    OTHERS(
        "系统", Icons.Filled.Android,
        listOf(
            BackupTarget.WIFI,
            BackupTarget.CONTACTS,
            BackupTarget.CALLS
        ),
        searchHint = "搜索",
        createLabel = "新建系统数据备份",
        manageLabel = "管理系统数据备份"
    ),

    /** 不是备份目标列表：内容由 [com.cc.libackupnext.ui.screen.SettingsContent] 自己管。 */
    SETTINGS("设置", Icons.Filled.Settings, emptyList());

    /** 这一页是不是「备份目标列表」。 */
    val isTargetList: Boolean get() = targets.isNotEmpty()
}

/** 每个页签的高度：图标 22dp + 上下各 10dp 内边距。 */
private val BOTTOM_ITEM_HEIGHT = 42.dp

/**
 * M3E 风格底栏：悬浮的圆角胶囊，选中项展开成「图标 + 文字」的药丸。
 * 选中块的位置由 [position] 决定，而不是等页签切完才跳 —— 拖动页面时它会跟着手指一起滑，
 * 文字宽度 / 颜色也按「离选中位置多远」连续过渡。
 */
@Composable
fun AppBottomBar(
    selected: HomeTab,
    onSelect: (HomeTab) -> Unit,
    containerColor: Color,
    modifier: Modifier = Modifier,
    /** 当前页签位置（0..3 的浮点数），默认就停在 [selected] 上。 */
    position: () -> Float = { selected.ordinal.toFloat() }
) {
    val items = HomeTab.entries
    val highlight = MaterialTheme.colorScheme.secondaryContainer
    val activeTint = MaterialTheme.colorScheme.onSecondaryContainer
    val idleTint = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle = MaterialTheme.typography.labelLarge
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current

    // 拖动过程中每帧都会读，所以整块只在这一层重组
    val current = position().coerceIn(0f, (items.size - 1).toFloat())

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 25.dp, top = 8.dp, end = 25.dp, bottom = 25.dp),
        shape = MaterialTheme.shapes.large,
        color = containerColor,
        shadowElevation = 2.dp
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp)
        ) {
            val slotWidth = maxWidth / items.size

            // 选中块：位置连续变化，拖动时和页面内容一起滑
            Box(
                modifier = Modifier
                    .offset(x = slotWidth * current)
                    .width(slotWidth)
                    .height(BOTTOM_ITEM_HEIGHT)
                    .clip(MaterialTheme.shapes.medium)
                    .background(highlight)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEachIndexed { index, item ->
                    // 0 = 离得最远，1 = 正好停在它上面
                    val weight = (1f - abs(current - index)).coerceIn(0f, 1f)
                    // 文字只在「过半」之后才展开：不然拖动到中间会两个页签各露半个字
                    val labelWeight = ((weight - 0.5f) * 2f).coerceIn(0f, 1f)
                    val labelWidth = with(density) {
                        measurer.measure(AnnotatedString(item.label), labelStyle)
                            .size.width.toDp()
                    }

                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(MaterialTheme.shapes.medium)
                            .clickable { if (item != selected) onSelect(item) }
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.label,
                            tint = lerp(idleTint, activeTint, weight),
                            modifier = Modifier.size(22.dp)
                        )
                        // 文字随「离选中位置多远」展开 / 收起，图标因此是平滑滑过去的
                        if (labelWeight > 0.01f) {
                            Spacer(modifier = Modifier.width(8.dp * labelWeight))
                            Box(
                                modifier = Modifier
                                    .width(labelWidth * labelWeight)
                                    .clipToBounds(),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Text(
                                    text = item.label,
                                    style = labelStyle,
                                    color = lerp(idleTint, activeTint, weight),
                                    // 宁可省略也不能折行：竖排的「文/件」比截断更难看
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.alpha(labelWeight)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
