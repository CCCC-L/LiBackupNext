package com.cc.libackupnext.data

/**
 * 快照元数据的解析规则，Kotlin 侧唯一的解释入口。
 *
 * - label 形如 `<包名>:<类型>`，类型有 apk / obb / user / user_de / data / icon
 * - tag 只有一个，用 `|` 连接：`应用名|版本|用户名|时间戳`
 *
 * 之所以拼成一个 tag：rustic 的 tags 是 BTreeSet，多个 tag 会被排序，
 * 顺序信息丢失；拼成一个整体就不会被拆开。
 */
object SnapshotMeta {

    private const val SEPARATOR = '|'

    fun tags(raw: String): List<String> = raw.split(',').filter { it.isNotBlank() }

    fun packageName(label: String): String = label.substringBeforeLast(':', label)

    /** 目录快照的 label 前缀：`dir:<绝对路径>`。 */
    const val DIR_LABEL_PREFIX = "dir:"

    /**
     * 从 label 解析内容类型。
     *
     * 应用是 `<包名>:<类型>`；目录是 `dir:<绝对路径>`，路径里本身带 `:` 和 `/`，
     * 不能按最后一个 `:` 切，否则类型会变成路径。
     *
     * 认不出来的类型返回 null：仓库里可能有本版本还不认识的快照。
     */
    fun kind(label: String): SnapshotKind? =
        if (label.startsWith(DIR_LABEL_PREFIX)) SnapshotKind.DIR
        else SnapshotKind.of(label.substringAfterLast(':'))

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
