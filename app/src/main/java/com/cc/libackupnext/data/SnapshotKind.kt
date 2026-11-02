package com.cc.libackupnext.data

/**
 * 快照的内容类型：label 冒号后面那部分，同时也是 rustic 的 stage 名。
 *
 * [key] 会写进仓库里已有快照的 label，改名等于改存档格式，不要动。
 * 这里只管协议，不带展示文案 —— 界面上要显示什么名字看 `SnapshotKind.displayName()`。
 */
enum class SnapshotKind(val key: String) {
    APK("apk"),
    OBB("obb"),
    USER("user"),
    USER_DE("user_de"),
    DATA("data"),
    PERMISSIONS("permissions"),
    DIR("dir"),
    ICON("icon");

    companion object {
        /** 容错解析：仓库里可能有本版本还不认识的类型，返回 null 而不是抛异常。 */
        fun of(key: String): SnapshotKind? = entries.firstOrNull { it.key == key }
    }
}

/**
 * 勾选单位：详情页 / 管理弹窗里一个勾选框覆盖的快照。
 * apk 连 obb 和权限快照一起备；user 连 user_de 一起备。
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
    DIR("dir", "dir", listOf(SnapshotKind.DIR));

    /** 这个勾选单位是否包含某个快照类型（null 表示解析不出来的未知类型）。 */
    fun covers(kind: SnapshotKind?): Boolean = kind != null && kind in kinds
}
