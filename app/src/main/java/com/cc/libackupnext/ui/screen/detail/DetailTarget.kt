package com.cc.libackupnext.ui.screen.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cc.libackupnext.data.AppInfo
import com.cc.libackupnext.data.BackupTarget
import com.cc.libackupnext.data.target.ItemAction
import com.cc.libackupnext.data.target.TargetSpecs
import com.cc.libackupnext.data.target.TargetViewKind
import com.cc.libackupnext.ui.component.AppView
import com.cc.libackupnext.ui.component.DirView
import com.cc.libackupnext.ui.component.SystemView
import com.cc.libackupnext.ui.component.TargetIconSize
import com.cc.libackupnext.ui.component.TargetView

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

    /**
     * 图标 + 图标下方的归属徽标（非主用户才有），竖排居中对齐。
     *
     * 徽标不叠在图标上：图标是应用自己的脸，压上去会看不清是谁的图标。
     * 宽度夹在 [TargetIconSize] 以内 —— 比图标宽会把右侧文字区顶偏，切用户时整页跟着抖。
     */
    @Composable
    fun Icon() {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            view.Icon()
            val caption = view.caption()
            if (caption != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Box(
                    modifier = Modifier.widthIn(max = TargetIconSize),
                    contentAlignment = Alignment.Center
                ) {
                    caption()
                }
            }
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

    override val actions: List<ItemAction> = TargetSpecs.of(BackupTarget.APP).actions(app)
}

/** 备份过的文件夹。 */
class DirTarget(
    private val app: AppInfo,
    isUninstalled: Boolean,
    onAction: (ItemAction) -> Unit,
    onBackup: () -> Unit,
) : DetailTarget(DirView(app, isUninstalled), onAction, onBackup) {

    override val version: String? = null

    override val actions: List<ItemAction> = TargetSpecs.of(BackupTarget.DIR).actions(app)
}

/** Wi-Fi / 联系人 / 通话记录：菜单里只有「打开系统对应的界面」。 */
class SystemTarget(
    private val target: BackupTarget,
    app: AppInfo,
    subtitle: String?,
    onAction: (ItemAction) -> Unit,
    onBackup: () -> Unit,
) : DetailTarget(SystemView(target, app, subtitle), onAction, onBackup) {

    override val version: String? = null

    override val actions: List<ItemAction> = TargetSpecs.of(target).actions(app)
}

/**
 * 按目标类型造对应的 [DetailTarget]。
 *
 * 骨架由 spec 的 [TargetViewKind] 决定 —— 加新类型只要在 `TargetSpecs` 注册一条，
 * 这里不用改。
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
): DetailTarget = when (TargetSpecs.of(kind).viewKind) {
    TargetViewKind.APP -> AppTarget(app, isUninstalled, latestVersion, onAction, onBackup)
    TargetViewKind.DIR -> DirTarget(app, isUninstalled, onAction, onBackup)
    TargetViewKind.SYSTEM -> SystemTarget(kind, app, subtitle, onAction, onBackup)
}
