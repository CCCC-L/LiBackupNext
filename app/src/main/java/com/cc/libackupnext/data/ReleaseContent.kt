package com.cc.libackupnext.data

/** 最新一个正式版 Release，UI 直接渲染。 */
data class ReleaseInfo(
    val version: String,
    val notes: List<ReleaseBlock>,
    val downloadUrl: String
)

/**
 * release body 解析后的一个块。
 *
 * 只保留 CI 生成的更新日志会用到的结构；不认识的行落到 [Paragraph] 原文照显，不崩。
 * [Heading.level] 保留 Markdown 原始的 1..6，UI 按等级给不同字号和间距。
 */
sealed interface ReleaseBlock {
    data class Heading(val text: String, val level: Int) : ReleaseBlock

    /** depth：前导缩进层级，`  - xxx` 是 1。 */
    data class Bullet(val text: String, val depth: Int) : ReleaseBlock

    data class Paragraph(val text: String) : ReleaseBlock
}
