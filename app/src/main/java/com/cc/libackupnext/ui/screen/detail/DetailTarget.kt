package com.cc.libackupnext.ui.screen.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.cc.libackupnext.data.BackupTarget
import com.cc.libackupnext.entity.AppInfo
import com.cc.libackupnext.ui.dialog.ItemAction
import com.cc.libackupnext.ui.util.AppView
import com.cc.libackupnext.ui.util.DirView
import com.cc.libackupnext.ui.util.SystemView
import com.cc.libackupnext.ui.util.TargetView

/**
 * 详情页展示的一个「目标」：应用、文件夹、Wi-Fi / 联系人 / 通话记录。
 *
 * 展示部分（图标 / 标题 / 副标题 / 在不在）委托给 [view]，列表行用的是同一份；
 * 这里只加详情页才有的东西：菜单动作和备份按钮。
 *
 * 加新类型时：在 [BackupTarget] 加枚举值 → [TargetView] 加子类 →
 * [detailTargetOf] 补分支，`when` 是穷尽的，漏了编译不过。
 */
sealed class DetailTarget(
    /** 展示层：列表行复用同一份。 */
    val view: TargetView,
    /** 菜单项被点了。 */
    val onAction: (ItemAction) -> Unit,
    /** 点「备份」按钮。 */
    val onBackup: () -> Unit,
) {

    /** 标题：应用名 / 文件夹名。 */
    val title: String get() = view.title

    /** 副标题：包名 / 绝对路径。 */
    val subtitle: String get() = view.subtitle

    /** 目标已经不存在：应用未安装 / 文件夹已删。 */
    val isUninstalled: Boolean get() = view.isMissing

    /** 版本号；没有就 null（文件夹）。 */
    abstract val version: String?

    /** 点开菜单时列出的动作，按显示顺序。 */
    abstract val actions: List<ItemAction>

    /** 图标，48dp 的方块由调用方给。 */
    @Composable
    fun Icon(modifier: Modifier) {
        Box(modifier) {
            view.Icon(Modifier.fillMaxSize())
            // 成员扩展函数要 with(分发接收者) 才能调，BoxScope 是隐式的扩展接收者
            with(view) { IconOverlay() }
        }
    }

    /** 标题前的小标记（冻结雪花之类），默认没有。 */
    @Composable
    fun Badge() = view.Badge()
}

/** 已安装 / 只在备份里存在的应用。 */
class AppTarget(
    private val app: AppInfo,
    isUninstalled: Boolean,
    /** 备份里最新记录的版本，应用没装时兜底 */
    private val latestVersion: String?,
    onAction: (ItemAction) -> Unit,
    onBackup: () -> Unit,
) : DetailTarget(AppView(app, isUninstalled), onAction, onBackup) {

    override val version: String? = app.version.ifBlank { latestVersion.orEmpty() }.ifBlank { null }

    override val actions: List<ItemAction> = buildList {
        add(ItemAction.LAUNCH)
        add(ItemAction.FORCE_STOP)
        add(if (app.isFrozen) ItemAction.UNFREEZE else ItemAction.FREEZE)
        add(ItemAction.APP_SETTINGS)
        add(ItemAction.CLEAR_DATA)
        add(ItemAction.UNINSTALL)
    }
}

/** 备份过的文件夹。 */
class DirTarget(
    path: String,
    isUninstalled: Boolean,
    onAction: (ItemAction) -> Unit,
    onBackup: () -> Unit,
) : DetailTarget(DirView(path, isUninstalled), onAction, onBackup) {

    override val version: String? = null

    override val actions: List<ItemAction> = listOf(ItemAction.OPEN_DIR, ItemAction.DELETE_DIR)
}

/** Wi-Fi / 联系人 / 通话记录：菜单里只有「打开系统对应的界面」。 */
class SystemTarget(
    private val target: BackupTarget,
    subtitle: String?,
    onAction: (ItemAction) -> Unit,
    onBackup: () -> Unit,
) : DetailTarget(SystemView(target, subtitle), onAction, onBackup) {

    override val version: String? = null

    override val actions: List<ItemAction> = when (target) {
        BackupTarget.WIFI -> listOf(
            ItemAction.WIFI_ON,
            ItemAction.WIFI_OFF,
            ItemAction.OPEN_WIFI_SETTINGS,
        )

        BackupTarget.CONTACTS -> listOf(ItemAction.OPEN_CONTACTS)
        BackupTarget.CALLS -> listOf(ItemAction.OPEN_CALL_LOG)
        else -> emptyList()
    }
}

/**
 * 按目标类型造对应的 [DetailTarget]。
 *
 * @param isUninstalled 由调用方算好 —— 它依赖「是否只在备份里」「刚卸载」「目录还在不在」
 *   这些页面状态，子类拿不到。
 */
fun detailTargetOf(
    kind: BackupTarget,
    app: AppInfo,
    isUninstalled: Boolean,
    latestVersion: String?,
    onAction: (ItemAction) -> Unit,
    onBackup: () -> Unit,
    /** 固定目标的副标题（「共 123 个」这类），其它类型不用传 */
    subtitle: String? = null,
): DetailTarget = when (kind) {
    BackupTarget.APP -> AppTarget(app, isUninstalled, latestVersion, onAction, onBackup)
    BackupTarget.DIR -> DirTarget(app.packageName, isUninstalled, onAction, onBackup)
    BackupTarget.WIFI, BackupTarget.CONTACTS,
    BackupTarget.CALLS ->
        SystemTarget(kind, subtitle, onAction, onBackup)
}
