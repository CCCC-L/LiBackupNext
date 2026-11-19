package com.cc.libackupnext.data

import kotlinx.serialization.Serializable

/**
 * 备份 / 恢复的进度事件。
 *
 * 它同时是**跨进程协议**：root 侧按这个结构写进共享内存的进度区，主进程按同样的字段解析
 *
 * @param stage 当前步骤的类型名（apk / obb / user / data / permissions / wifi ...）
 * @param state running / done / skipped / finished
 */
@Serializable
data class BackupProgress(
    val stage: String,
    val state: String,
    val files: Long = 0,
    val reason: String = ""
)
