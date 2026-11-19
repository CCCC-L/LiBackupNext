package com.cc.libackupnext.data

/**
 * 快照的内容类型：label 冒号后面那部分，同时也是 rustic 的 stage 名。
 *
 * [key] 会写进仓库里已有快照的 label，改名等于改存档格式，不要动。
 * 这里只管协议，不带展示文案 —— 界面上直接显示 key。
 */
enum class SnapshotKind(val key: String) {
    APK("apk"),
    OBB("obb"),
    USER("user"),
    USER_DE("user_de"),
    DATA("data"),
    PERMISSIONS("permissions"),
    DIR("dir"),
    ICON("icon"),

    /** 已保存的 Wi-Fi（含密码，来自 WifiManager 的私有配置列表）。 */
    WIFI("wifi"),

    /** 联系人：RawContacts + Data 行导出的 JSON。 */
    CONTACTS("contacts"),

    /** 通话记录。 */
    CALLS("calls"),

    /** 短信：content://sms 的标准列。 */
    SMS("sms"),

    /** 彩信：content://mms + 它的 addr / part 子表。 */
    MMS("mms");

    companion object {
        /** 容错解析：仓库里可能有本版本还不认识的类型，返回 null 而不是抛异常。 */
        fun of(key: String): SnapshotKind? = entries.firstOrNull { it.key == key }

        /**
         * 系统数据的类型集合：这几个不是文件快照，备份前要由 root 侧导出成 JSON。
         *
         * 定义放在这里（而不是从 Handler 注册表反推），是为了让**纯逻辑**也能判断
         * 「这个快照是不是系统数据」，不必初始化一整套 Handler（那会拖进 Android 依赖，
         * 让恢复前置判断没法单测）。Handler 注册表与这个列表的一致性由测试保证。
         */
        val SYSTEM_KINDS: List<SnapshotKind> = listOf(WIFI, CONTACTS, CALLS, SMS, MMS)
    }
}

/**
 * 勾选单位：详情页 / 管理弹窗里一个勾选框覆盖的快照。
 * apk 连 obb 和权限快照一起备；user 连 user_de 一起备；
 * 系统数据（Wi-Fi / 联系人 / 通话记录）各自只有一个快照，没有可选项。
 *
 * @param title 勾选框 / 列头的短名
 * @param optionLabel 备份选项弹窗里那一行的说明（apk 要说明含 obb 和权限）
 * @param kinds 勾上这个单位会产出的快照类型
 */
enum class BackupGroup(
    val title: String,
    val optionLabel: String,
    val kinds: List<SnapshotKind>
) {
    APK("apk", "apk(含 obb、权限)", listOf(SnapshotKind.APK, SnapshotKind.OBB, SnapshotKind.PERMISSIONS)),
    USER("user", "user", listOf(SnapshotKind.USER, SnapshotKind.USER_DE)),
    DATA("data", "data", listOf(SnapshotKind.DATA)),
    DIR("dir", "dir", listOf(SnapshotKind.DIR)),

    WIFI("wifi", "wifi", listOf(SnapshotKind.WIFI)),
    CONTACTS("contacts", "contacts", listOf(SnapshotKind.CONTACTS)),
    CALLS("calls", "calls", listOf(SnapshotKind.CALLS)),

    /** 短信：和彩信共用「短信」这一个目标，但在备份弹窗里是各自独立的一项。 */
    SMS("短信", "短信", listOf(SnapshotKind.SMS)),

    /** 彩信：附件会一起进快照，体积比短信大得多。 */
    MMS("彩信", "彩信", listOf(SnapshotKind.MMS));

    /** 这个勾选单位是否包含某个快照类型（null 表示解析不出来的未知类型）。 */
    fun covers(kind: SnapshotKind?): Boolean = kind != null && kind in kinds
}
