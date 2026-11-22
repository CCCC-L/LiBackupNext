package com.cc.libackupnext.data

import kotlinx.serialization.Serializable

/**
 * 备份 / 恢复的进度事件。
 *
 * 它同时是**跨进程协议**：root 侧按这个结构写进共享内存的进度区，主进程按同样的字段解析
 *
 * @param stage 当前步骤的类型名（apk / obb / user / data / permissions / wifi ...）
 * @param state running / done / skipped / finished / **progress**
 *   `progress` 是字节进度帧：只更新 [total] / [current]，不改变步骤状态
 * @param files 这一步处理了多少文件
 * @param errors 这一步有多少个源文件读不出来（rustic 只跳过它们、照样出快照，
 *   所以必须单独报出来，否则「完成」会骗人）
 * @param total 字节进度的总量；0 表示 rustic 还没扫出来（源目录为空或扫描未完成）
 * @param current 字节进度已处理量
 */
@Serializable
data class BackupProgress(
    val stage: String,
    val state: String,
    val files: Long = 0,
    val errors: Long = 0,
    val reason: String = "",
    val total: Long = 0,
    val current: Long = 0
) {
    companion object {
        /** 字节进度帧的状态名。 */
        const val STATE_PROGRESS = "progress"
    }
}
