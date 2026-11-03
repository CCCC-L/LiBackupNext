package com.cc.libackupnext.data

import com.cc.libackupnext.entity.AppInfo
import com.cc.libackupnext.entity.dirAppInfo
import com.cc.libackupnext.entity.isDirectory
import java.io.File

/**
 * 批量页 / 列表页备份的「目标类型」。
 *
 * 每种类型自己决定：列表怎么来、统计里叫什么单位、快照有哪些内容类型（列头那几列）。
 * 以后要加「数据库备份」「微信记录」这类，只要在这里加一个枚举值 + 补一个 [targets] 分支。
 *
 * @param unit 统计文字里的单位，例如「个应用」
 * @param kinds 可勾选的单位，也是列表顶部的列
 * @param fixedId 固定目标在仓库里的标识，也是伪 AppInfo 的 packageName；
 *   应用 / 文件夹由数据决定，这里是 null
 * @param displayName 固定目标的显示名
 */
enum class BackupTarget(
    val unit: String,
    val kinds: List<BackupGroup>,
    val fixedId: String? = null,
    val displayName: String = ""
) {

    /** 已安装的应用：apk / user / data 三列。 */
    APP("个应用", listOf(BackupGroup.APK, BackupGroup.USER, BackupGroup.DATA)),

    /** 目录备份：只有一列 dir，快照 label 是 `<绝对路径>:dir`。 */
    DIR("个文件夹", listOf(BackupGroup.DIR)),

    /** 已保存的 Wi-Fi（含密码）：一个快照，内容是导出出来的 JSON。 */
    WIFI("个Wi-Fi", listOf(BackupGroup.WIFI), fixedId = "wifi", displayName = "Wi-Fi"),

    /** 联系人：RawContacts + Data 行导出的 JSON。 */
    CONTACTS("个联系人", listOf(BackupGroup.CONTACTS), fixedId = "contacts", displayName = "联系人"),

    /** 通话记录。 */
    CALLS("条记录", listOf(BackupGroup.CALLS), fixedId = "calls", displayName = "通话记录");

    /** 是不是「应用」这种目标。目前只用来决定详情页 / 批量页显不显示用户切换按钮。 */
    val isApp: Boolean get() = this == APP

    /** 内容固定、永远只有一行的目标（Wi-Fi / 联系人 / 通话记录）。 */
    val isFixed: Boolean get() = fixedId != null

    /** 详情页顶栏标题。 */
    val detailTitle: String get() = when (this) {
        APP -> "应用信息"
        DIR -> "文件信息"
        WIFI -> "Wi-Fi 信息"
        CONTACTS -> "联系人信息"
        CALLS -> "通话记录信息"
    }

    /** 详情页「帮助」里显示的内容，一行一段。 */
    val helpLines: List<String> get() = when (this) {
        APP -> listOf(
            "apk：应用安装包（含 obb），支持分包应用 (Split APK)",
            "user：位于 /data/user/ 下的数据",
            "data：位于共享存储 /Android/data/ 下的数据",
            "权限：已授予的运行时权限随 apk 一起备份，恢复时重新授予：「仅本次允许」这类临时授权不备份"
        )

        DIR -> listOf(
            "备份：跳过.thumbnails 文件夹及.trashed 开头的文件/文件夹",
            "恢复：覆盖同名文件，保留新增文件"
        )

        WIFI -> listOf(
            "恢复：同名Wi-Fi 已存在就跳过，不会覆盖现有密码",
            "注：企业网络（802.1X）能备份，但恢复不了",
            "注：备份时建议用其他软件也备份一次"
        )

        CONTACTS -> listOf(
            "恢复：同名联系人的不同号会合并恢复",
            "注：备份时建议用其他软件也备份一次"
        )

        CALLS -> listOf(
            "注：备份时建议用其他软件也备份一次"
        )
    }

    /**
     * 这次备份要产出的内容。
     *
     * 应用：用户勾了哪些就是哪些；
     * 其余：整个全备，没有可选项，勾选状态忽略。
     */
    fun backupKinds(checked: Set<BackupGroup>): Set<BackupGroup> = when (this) {
        APP -> checked intersect kinds.toSet()
        else -> kinds.toSet()
    }

    /**
     * 这一页要列的目标。
     *
     * @param apps 应用列表（[APP] 用）
     * @param create true = 新建备份页（全部目标），false = 管理页（只留有备份的）
     * @param hasBackup 这个包名 / 路径在仓库里有没有备份
     */
    fun targets(
        apps: List<AppInfo>,
        create: Boolean,
        hasBackup: (String) -> Boolean
    ): List<AppInfo> {
        val all = when (this) {
            APP -> apps.filterNot { create && it.onlyInBackup }
            DIR -> AppPrefs.extraDirs
                .filterNot { create && !File(it).isDirectory }
                .map { dirAppInfo(it) }
            // 固定目标永远只有一行，和系统里有没有数据无关
            WIFI, CONTACTS, CALLS -> listOf(fixedAppInfo())
        }
        return if (create) all else all.filter { hasBackup(it.packageName) }
    }

    /** 固定目标的伪应用：packageName 就是 [fixedId]，name 是显示名。 */
    fun fixedAppInfo(): AppInfo = AppInfo(
        name = displayName,
        packageName = fixedId.orEmpty(),
        version = "",
        versionCode = 0,
        installTime = 0,
        updateTime = 0,
        userId = 0,
        isSystem = false,
        isFrozen = false
    )

    companion object {
        /** 固定目标的 id（wifi / contacts / calls），用来判断一个「包名」是不是系统数据。 */
        val fixedIds: Set<String> = entries.mapNotNull { it.fixedId }.toSet()

        /** 从「伪应用」反推目标类型：先认固定目标，再看是不是路径。 */
        fun of(app: AppInfo): BackupTarget =
            entries.firstOrNull { it.fixedId != null && it.fixedId == app.packageName }
                ?: if (app.isDirectory) DIR else APP
    }
}
