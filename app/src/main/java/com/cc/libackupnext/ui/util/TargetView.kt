package com.cc.libackupnext.ui.util

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cc.libackupnext.data.BackupTarget
import com.cc.libackupnext.entity.AppInfo
import com.cc.libackupnext.ui.UserBadge
import com.cc.libackupnext.ui.isNonPrimaryUser
import java.io.File

/**
 * 一个备份目标「长什么样」：列表行和详情页 header 共用这一份。
 *
 * 只管展示，不持有 UI 状态，也不碰「点了做什么」—— 那是
 * `com.cc.libackupnext.ui.screen.detail.DetailTarget` 的事。
 * 加新类型时：在 [BackupTarget] 加枚举值 → 这里加子类 → [targetViewOf] 补分支，
 * `when` 是穷尽的，漏了编译不过。
 */
sealed class TargetView(
    /** 这个视图描述的是哪种目标。 */
    val target: BackupTarget,
    /** 目标数据：应用 / 文件夹 / 固定目标都伪装成一个 [AppInfo]。 */
    protected val app: AppInfo
) {

    /** 标题：应用名 / 文件夹名。 */
    abstract val title: String

    /** 副标题：包名 / 绝对路径。 */
    abstract val subtitle: String

    /** 目标已经不存在：只在备份里的应用 / 已经被删的文件夹。 */
    abstract val isMissing: Boolean

    /** 标题前要不要显示冻结雪花。 */
    open val isFrozen: Boolean = false

    /** 标题颜色；null 用默认色（不存在时灰、否则 onSurface）。 */
    open val titleColor: Color?
        @Composable get() = null

    /**
     * 图标
     *
     * @param size 方块边长，由调用方按版面给（列表行 / 详情页 48，批量页 40）
     */
    @Composable
    fun Icon(size: Dp = 48.dp) {
        TargetIconBox(target, app, size)
    }

    /** 标题前的小标记（冻结雪花之类），默认没有。自己负责后面的间距。 */
    @Composable
    open fun Badge() {
    }

    /** 叠在图标右下角的小徽标（详情页 header 用），默认没有。 */
    @Composable
    open fun BoxScope.IconOverlay() {
    }

    /**
     * 行尾附加内容（列表行用），没有就返回 null。
     *
     * 返回 null 而不是「渲染一个空 Composable」，是为了让列表行不加多余间距 ——
     * 主用户的应用没有徽标，那一行右侧不该凭空多出 12dp。
     */
    open fun trailing(): (@Composable () -> Unit)? = null
}

/** 已安装 / 只在备份里存在的应用。 */
class AppView(
    app: AppInfo,
    override val isMissing: Boolean,
) : TargetView(BackupTarget.APP, app) {

    override val title: String = app.name
    override val subtitle: String = app.packageName
    override val isFrozen: Boolean = app.isFrozen

    /** 系统应用标题标红，一眼看出哪些是预装的。 */
    override val titleColor: Color?
        @Composable get() = systemAppTitleColor(app.isSystem)

    @Composable
    override fun Badge() {
        if (isFrozen) {
            FrozenSnowflake()
            Spacer(modifier = Modifier.width(4.dp))
        }
    }

    @Composable
    override fun BoxScope.IconOverlay() {
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

    override fun trailing(): (@Composable () -> Unit)? =
        if (isNonPrimaryUser(app.userId)) {
            { UserBadge(userId = app.userId) }
        } else {
            null
        }
}

/** 备份过的文件夹。 */
class DirView(
    app: AppInfo,
    override val isMissing: Boolean,
) : TargetView(BackupTarget.DIR, app) {

    private val path: String = app.packageName
    private val normalized: String = path.trimEnd('/').ifBlank { path }

    override val title: String = normalized.substringAfterLast('/').ifBlank { normalized }
    override val subtitle: String = path
}

/**
 * Wi-Fi / 联系人 / 通话记录：内容固定的目标，永远只有一行。
 *
 * @param subtitleText 副标题，由调用方算好（「已保存 12 个」这类），读不到就传 null
 */
class SystemView(
    target: BackupTarget,
    app: AppInfo,
    private val subtitleText: String?,
) : TargetView(target, app) {

    override val title: String = target.displayName
    override val subtitle: String = subtitleText.orEmpty()
    override val isMissing: Boolean = false
}

/** 按目标类型造视图。 */
fun targetViewOf(
    target: BackupTarget,
    app: AppInfo,
    isMissing: Boolean,
    subtitle: String? = null,
): TargetView = when (target) {
    BackupTarget.APP -> AppView(app, isMissing)
    BackupTarget.DIR -> DirView(app, isMissing)
    BackupTarget.WIFI, BackupTarget.CONTACTS,
    BackupTarget.CALLS -> SystemView(target, app, subtitle)
}

/**
 * 这个目标在列表里是不是已经不存在了。
 *
 * 应用看「是不是只在备份里」，文件夹直接看磁盘 —— 目录删了但还有备份时，
 * 行要划线置灰而不是消失。
 */
fun BackupTarget.isMissing(app: AppInfo): Boolean = when (this) {
    BackupTarget.APP -> app.onlyInBackup
    BackupTarget.DIR -> !File(app.packageName).isDirectory
    // 系统数据只要 provider 读得到就有；读不到时备份会自己跳过并给出说明
    BackupTarget.WIFI, BackupTarget.CONTACTS,
    BackupTarget.CALLS -> false
}
