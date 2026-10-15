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

    fun kind(label: String): String = label.substringAfterLast(':')

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
