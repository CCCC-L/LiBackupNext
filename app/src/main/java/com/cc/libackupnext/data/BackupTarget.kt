package com.cc.libackupnext.data

import com.cc.libackupnext.entity.AppInfo
import com.cc.libackupnext.entity.DefaultBackupDirs
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
            DIR -> backupDirs(AppPrefs.extraDirs)
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

/**
 * 文件页的目录列表：默认那几个按固定顺序排前面，用户自己加的按字母序排后面。
 *
 * [extra] 已经包含默认目录了（`AppPrefs.extraDirs` 的默认值就是它们），所以这里不再补。
 */
fun backupDirs(extra: Set<String>): List<String> =
    DefaultBackupDirs.filter { it in extra } + extra.filterNot { it in DefaultBackupDirs }.sorted()
