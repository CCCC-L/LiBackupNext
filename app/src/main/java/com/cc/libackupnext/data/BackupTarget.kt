package com.cc.libackupnext.data

/**
 * 批量页 / 列表页备份的「目标类型」。
 *
 * 这里只有**元数据**：统计单位、有哪些内容类型、固定目标的 id 和显示名。
 * 「这一页该列哪些行」是业务规则，在 `service.target.TargetListing` 里。
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
    CALLS("条记录", listOf(BackupGroup.CALLS), fixedId = "calls", displayName = "通话记录"),

    /**
     * 短彩信：一个目标，底下两个快照分开勾选。
     *
     * 合成一个目标是因为用户认知里它们是同一个「信息」；拆成两个快照是因为
     * 彩信带附件、体积大得多，失败也要能隔离。
     */
    SMS(
        "条记录",
        listOf(BackupGroup.SMS, BackupGroup.MMS),
        fixedId = "messaging",
        displayName = "短彩信"
    );

    /** 是不是「应用」这种目标。目前只用来决定详情页 / 批量页显不显示用户切换按钮。 */
    val isApp: Boolean get() = this == APP

    /** 内容固定、永远只有一行的目标（Wi-Fi / 联系人 / 通话记录 / 短彩信）。 */
    val isFixed: Boolean get() = fixedId != null

    /**
     * 这次备份要产出的内容。
     *
     * 只有一项的类型整体全备（没有可选项，勾选状态忽略）；
     * 有多项的类型（应用、短信）按勾选来 —— 判据和备份弹窗用的是同一个。
     */
    fun backupKinds(checked: Set<BackupGroup>): Set<BackupGroup> =
        if (kinds.size == 1) kinds.toSet() else checked intersect kinds.toSet()

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
