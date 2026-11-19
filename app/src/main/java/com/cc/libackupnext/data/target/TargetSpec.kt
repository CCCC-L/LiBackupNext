package com.cc.libackupnext.data.target

import com.cc.libackupnext.data.BackupTarget
import com.cc.libackupnext.data.SnapshotKind
import com.cc.libackupnext.data.AppInfo
import java.io.File

/** 底部导航把目标分成哪几组。加新类型时选一个，底栏会自动出现它。 */
enum class TargetNavGroup { APP, DIR, OTHER }

/**
 * 列表行 / 详情页 header 用哪种视图骨架。
 *
 * 是「形态」而不是「渲染代码」：data 层不该知道 Composable。
 * UI 按这个值选骨架（应用图标行 / 文件夹行 / 系统数据行）。
 */
enum class TargetViewKind { APP, DIR, SYSTEM }

/**
 * 一种备份目标的**全部知识**：叫什么、长什么样、能做什么、帮什么忙。
 *
 * 加一种类型 = 注册一条 spec + 加一个 Handler + 加一个 SnapshotKind。
 * 漏注册会被 `TargetSpecsTest` 拦住，不会拖到真机上才发现。
 *
 * 刻意**不含**图标本体（[ImageVector] 是 Compose 类型）：只存 [iconKey] 语义名，
 * UI 侧按名字查表。这样 data 层保持纯净，也能在 JVM 单测里跑。
 *
 * @param target 对应的目标枚举
 * @param navGroup 底部导航分组
 * @param label 底栏 / 列表里的短名
 * @param detailTitle 详情页顶栏标题
 * @param iconKey 图标语义名，UI 侧 `TargetIcon` 按它查矢量图
 * @param viewKind 列表行 / 详情页用哪种骨架
 * @param countText 「其他」页副标题（已保存 12 个 / 共 86 条）；应用和目录用空串
 * @param helpLines 详情页帮助文案，一行一段
 * @param actions 详情页菜单里的动作；需要看应用状态时用 [AppInfo] 算
 * @param isMissing 这个目标在列表里是不是已经不存在了（应用只在备份里 / 文件夹已删）
 */
data class TargetSpec(
    val target: BackupTarget,
    val navGroup: TargetNavGroup,
    val label: String,
    val detailTitle: String,
    val iconKey: String,
    val viewKind: TargetViewKind,
    val countText: (Map<SnapshotKind, Int>) -> String = { "" },
    val helpLines: List<String> = emptyList(),
    val actions: (AppInfo) -> List<ItemAction> = { emptyList() },
    val isMissing: (AppInfo) -> Boolean = { false },
)

/**
 * 目标注册表：新增类型时只改这里 + 一个 Handler + 一个 [SnapshotKind]。
 *
 * 顺序就是「其他」页的显示顺序。
 */
object TargetSpecs {

    /**
     * 「用应用自己的图标」这个约定值。
     *
     * 应用目标没有固定矢量图标，UI 见到它就去找 AppInfo 的图标；
     * 用常量而不是散落的字面量，避免两边写错也编译得过。
     */
    const val APP_ICON_KEY = "app"

    private val all: List<TargetSpec> = listOf(
        TargetSpec(
            target = BackupTarget.APP,
            navGroup = TargetNavGroup.APP,
            label = "应用",
            detailTitle = "应用信息",
            iconKey = APP_ICON_KEY,
            viewKind = TargetViewKind.APP,
            helpLines = listOf(
                "apk：应用安装包（含 obb），支持分包应用 (Split APK)",
                "user：位于 /data/user/ 下的数据",
                "data：位于共享存储 /Android/data/ 下的数据",
                "权限：已授予的运行时权限随 apk 一起备份，恢复时重新授予：「仅本次允许」这类临时授权不备份"
            ),
            actions = { app ->
                listOf(
                    ItemAction.LAUNCH,
                    ItemAction.FORCE_STOP,
                    if (app.isFrozen) ItemAction.UNFREEZE else ItemAction.FREEZE,
                    ItemAction.APP_SETTINGS,
                    ItemAction.CLEAR_DATA,
                    ItemAction.UNINSTALL
                )
            },
            isMissing = { app -> app.onlyInBackup }
        ),

        TargetSpec(
            target = BackupTarget.DIR,
            navGroup = TargetNavGroup.DIR,
            label = "文件",
            detailTitle = "文件信息",
            iconKey = "folder",
            viewKind = TargetViewKind.DIR,
            helpLines = listOf(
                "备份：跳过.thumbnails 文件夹及.trashed 开头的文件/文件夹",
                "恢复：覆盖同名文件，保留新增文件"
            ),
            actions = { listOf(ItemAction.OPEN_DIR, ItemAction.DELETE_DIR) },
            isMissing = { app -> !File(app.packageName).isDirectory }
        ),

        TargetSpec(
            target = BackupTarget.WIFI,
            navGroup = TargetNavGroup.OTHER,
            label = "Wi-Fi",
            detailTitle = "Wi-Fi",
            iconKey = "wifi",
            viewKind = TargetViewKind.SYSTEM,
            countText = { counts ->
                counts[SnapshotKind.WIFI]?.let { "已保存 $it 个" } ?: "—"
            },
            helpLines = listOf(
                "恢复：同名Wi-Fi 已存在就跳过，不会覆盖现有密码",
                "注：企业网络（802.1X）能备份，但恢复不了",
                "注：备份时建议用其他软件也备份一次"
            ),
            actions = {
                listOf(
                    ItemAction.WIFI_ON,
                    ItemAction.WIFI_OFF,
                    ItemAction.OPEN_WIFI_SETTINGS
                )
            }
        ),

        TargetSpec(
            target = BackupTarget.CONTACTS,
            navGroup = TargetNavGroup.OTHER,
            label = "联系人",
            detailTitle = "联系人",
            iconKey = "contacts",
            viewKind = TargetViewKind.SYSTEM,
            countText = { counts ->
                counts[SnapshotKind.CONTACTS]?.let { "共 $it 个" } ?: "—"
            },
            helpLines = listOf(
                "恢复：同名联系人的不同号会合并恢复",
                "注：备份时建议用其他软件也备份一次"
            ),
            actions = { listOf(ItemAction.OPEN_CONTACTS) }
        ),

        TargetSpec(
            target = BackupTarget.CALLS,
            navGroup = TargetNavGroup.OTHER,
            label = "通话记录",
            detailTitle = "通话记录",
            iconKey = "calls",
            viewKind = TargetViewKind.SYSTEM,
            countText = { counts ->
                counts[SnapshotKind.CALLS]?.let { "共 $it 条" } ?: "—"
            },
            helpLines = listOf(
                "注：备份时建议用其他软件也备份一次"
            ),
            actions = { listOf(ItemAction.OPEN_CALL_LOG) }
        ),

        TargetSpec(
            target = BackupTarget.SMS,
            navGroup = TargetNavGroup.OTHER,
            label = "短彩信",
            detailTitle = "短彩信",
            iconKey = "sms",
            viewKind = TargetViewKind.SYSTEM,
            // 短信和彩信分开列
            countText = { counts ->
                BackupTarget.SMS.kinds
                    .mapNotNull { group ->
                        group.kinds.firstOrNull()?.let { kind -> counts[kind] }?.let { group to it }
                    }
                    .joinToString(" · ") { (group, count) -> "${group.title} $count" }
                    .ifBlank { "—" }
            },
            helpLines = listOf(
                "短信、彩信：只备份标准字段，厂商私有字段（气泡、RCS 标记等）不备份",
                "恢复：已存在的消息会跳过，不会重复写入"
            )
        )
    )

    private val byTarget: Map<BackupTarget, TargetSpec> = all.associateBy { it.target }

    /** 按目标取 spec；没注册说明加类型时漏了一步，直接崩比默默显示空白好。 */
    fun of(target: BackupTarget): TargetSpec =
        byTarget[target] ?: error("未注册 $target 的 TargetSpec")

    /** 底部某个分组下的目标，顺序即显示顺序。 */
    fun byNavGroup(group: TargetNavGroup): List<BackupTarget> =
        all.filter { it.navGroup == group }.map { it.target }

    /** 全部已注册的 spec，供一致性测试遍历。 */
    fun specs(): List<TargetSpec> = all

    /** 所有用到的图标语义名，供 UI 侧校验图标表是否齐全。 */
    fun iconKeys(): Set<String> = all.mapTo(LinkedHashSet()) { it.iconKey }
}
