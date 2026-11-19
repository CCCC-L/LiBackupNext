package com.cc.libackupnext.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.cc.libackupnext.data.BackupTarget
import com.cc.libackupnext.data.target.TargetNavGroup
import com.cc.libackupnext.data.target.TargetSpecs

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
        "应用", Icons.Filled.Apps, TargetSpecs.byNavGroup(TargetNavGroup.APP),
        searchHint = "搜索应用名称或包名",
        createLabel = "新建应用备份",
        manageLabel = "管理应用备份"
    ),

    FILES(
        "文件", Icons.Filled.Folder, TargetSpecs.byNavGroup(TargetNavGroup.DIR),
        searchHint = "搜索文件夹路径或名称",
        createLabel = "新建文件备份",
        manageLabel = "管理文件备份"
    ),

    /**
     * Wi-Fi / 联系人 / 通话记录 / 短彩信：一页列多个固定目标。
     *
     * 列表从注册表来，不在这里硬编码 —— 加一种系统数据类型时只改 `TargetSpecs`，
     * 这个 tab 会自动多出一行。
     */
    OTHERS(
        "系统", Icons.Filled.Android, TargetSpecs.byNavGroup(TargetNavGroup.OTHER),
        searchHint = "搜索",
        createLabel = "新建系统数据备份",
        manageLabel = "管理系统数据备份"
    ),

    /** 不是备份目标列表：内容由 [com.cc.libackupnext.ui.screen.SettingsContent] 自己管。 */
    SETTINGS("设置", Icons.Filled.Settings, emptyList());

    /** 这一页是不是「备份目标列表」。 */
    val isTargetList: Boolean get() = targets.isNotEmpty()
}
