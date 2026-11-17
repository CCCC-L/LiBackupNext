package com.cc.libackupnext.data

/**
 * 快照元数据的解析规则，Kotlin 侧唯一的解释入口。
 *
 * - label 统一是 `<键>:<类型>`，类型就是 [SnapshotKind.key]
 * - 键随目标而变：应用是包名（`com.foo:apk`），固定目标是 [BackupTarget.fixedId]
 *   （`messaging:sms`、`wifi:wifi`），目录是绝对路径（`/sdcard/DCIM:dir`）
 * - tag 只有一个，用 `|` 连接：`应用名|版本|用户名|时间戳`
 *
 * 之所以拼成一个 tag：rustic 的 tags 是 BTreeSet，多个 tag 会被排序，
 * 顺序信息丢失；拼成一个整体就不会被拆开。
 */
object SnapshotMeta {

    private const val SEPARATOR = '|'

    fun tags(raw: String): List<String> = raw.split(',').filter { it.isNotBlank() }

    /** label 里 `:` 之前那段：应用是包名，固定目标是 fixedId，目录是绝对路径。 */
    fun packageName(label: String): String = label.substringBeforeLast(':', label)

    /**
     * 从 label 解析内容类型：取**最后**一个 `:` 之后那段。
     *
     * 键里本身可能带 `:`（Linux 允许文件名含冒号），但类型永远在最后，所以不能按
     * 第一个 `:` 切。目录快照的键是绝对路径（`/sdcard/DCIM:dir`），按第一个切会把
     * 类型解析成 `/sdcard/DCIM` 这种路径。
     *
     * 认不出来的类型返回 null：仓库里可能有本版本还不认识的快照。
     */
    fun kind(label: String): SnapshotKind? = SnapshotKind.of(label.substringAfterLast(':'))

    /** 把 `应用名|版本|用户名|时间戳` 拆开。 */
    fun fields(rawTags: String): List<String> =
        tags(rawTags).firstOrNull { it.contains(SEPARATOR) }?.split(SEPARATOR)
            ?: tags(rawTags)

    /** 应用名。 */
    fun appName(rawTags: String): String? = fields(rawTags).getOrNull(0)?.takeIf { it.isNotBlank() }

    /** 版本号，形如 `1.10.2(230102)`。 */
    fun version(rawTags: String): String? = fields(rawTags).getOrNull(1)?.takeIf { it.isNotBlank() }

    /** 备份时的用户名。 */
    fun userName(rawTags: String): String? = fields(rawTags).getOrNull(2)?.takeIf { it.isNotBlank() }

    /** 备份时间戳（毫秒）。 */
    fun timestamp(rawTags: String): Long? = fields(rawTags).getOrNull(3)?.toLongOrNull()
}
