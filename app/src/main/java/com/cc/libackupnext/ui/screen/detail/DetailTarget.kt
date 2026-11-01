package com.cc.libackupnext.ui.screen.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cc.libackupnext.data.BackupTarget
import com.cc.libackupnext.entity.AppInfo
import com.cc.libackupnext.ui.UserBadge
import com.cc.libackupnext.ui.dialog.ItemAction
import com.cc.libackupnext.ui.isNonPrimaryUser
import com.cc.libackupnext.ui.util.AppIcon
import com.cc.libackupnext.ui.util.FolderIconBox
import com.cc.libackupnext.ui.util.FrozenSnowflake
import com.cc.libackupnext.ui.util.rememberAppIconWithBackup

/**
 * 详情页展示的一个「目标」：应用、文件夹，以后可能是 Wi-Fi / 短信 / 通话记录。
 *
 * 描述「怎么展示、能做什么、点了怎么响应」，不持有 UI 状态
 * （菜单展开、忙碌标记这些留在 [DetailHeader] 里）。
 *
 * 加新类型时：在 [BackupTarget] 加枚举值 → 这里加子类 → [detailTargetOf] 补分支，
 * `when` 是穷尽的，漏了编译不过。
 */
sealed class DetailTarget(
    /** 菜单项被点了。 */
    val onAction: (ItemAction) -> Unit,
    /** 点「备份」按钮。 */
    val onBackup: () -> Unit,
) {

    /** 标题：应用名 / 文件夹名。 */
    abstract val title: String

    /** 副标题：包名 / 绝对路径。 */
    abstract val subtitle: String

    /** 版本号；没有就 null（文件夹）。 */
    abstract val version: String?

    /** 目标已经不存在：应用未安装 / 文件夹已删。 */
    abstract val isUninstalled: Boolean

    /** 点开菜单时列出的动作，按显示顺序。 */
    abstract val actions: List<ItemAction>

    /** 图标，48dp 的方块由调用方给。 */
    @Composable
    abstract fun Icon(modifier: Modifier)

    /** 标题前的小标记（冻结雪花之类），默认没有。自己负责后面的间距。 */
    @Composable
    open fun Badge() {}
}

/** 已安装 / 只在备份里存在的应用。 */
class AppTarget(
    private val app: AppInfo,
    override val isUninstalled: Boolean,
    /** 备份里最新记录的版本，应用没装时兜底 */
    private val latestVersion: String?,
    onAction: (ItemAction) -> Unit,
    onBackup: () -> Unit,
) : DetailTarget(onAction, onBackup) {

    override val title: String = app.name
    override val subtitle: String = app.packageName
    override val version: String? = app.version.ifBlank { latestVersion.orEmpty() }.ifBlank { null }

    override val actions: List<ItemAction> = buildList {
        add(ItemAction.LAUNCH)
        add(ItemAction.FORCE_STOP)
        add(if (app.isFrozen) ItemAction.UNFREEZE else ItemAction.FREEZE)
        add(ItemAction.APP_SETTINGS)
        add(ItemAction.CLEAR_DATA)
        add(ItemAction.UNINSTALL)
    }

    @Composable
    override fun Icon(modifier: Modifier) {
        Box(modifier) {
            AppIcon(
                icon = rememberAppIconWithBackup(app.packageName, app.userId),
                name = app.name,
                modifier = Modifier.fillMaxSize()
            )
            if (isNonPrimaryUser(app.userId)) {
                UserBadge(
                    userId = app.userId,
                    compact = true,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(2.dp)
                )
            }
        }
    }

    @Composable
    override fun Badge() {
        if (app.isFrozen) {
            FrozenSnowflake()
            Spacer(modifier = Modifier.width(4.dp))
        }
    }
}

/** 备份过的文件夹。 */
class DirTarget(
    private val path: String,
    override val isUninstalled: Boolean,
    onAction: (ItemAction) -> Unit,
    onBackup: () -> Unit,
) : DetailTarget(onAction, onBackup) {

    private val normalized: String = path.trimEnd('/').ifBlank { path }

    override val title: String = normalized.substringAfterLast('/').ifBlank { normalized }
    override val subtitle: String = path
    override val version: String? = null

    override val actions: List<ItemAction> = listOf(ItemAction.OPEN_DIR, ItemAction.DELETE_DIR)

    @Composable
    override fun Icon(modifier: Modifier) {
        Box(modifier) { FolderIconBox() }
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
): DetailTarget = when (kind) {
    BackupTarget.APP -> AppTarget(app, isUninstalled, latestVersion, onAction, onBackup)
    BackupTarget.DIR -> DirTarget(app.packageName, isUninstalled, onAction, onBackup)
}
