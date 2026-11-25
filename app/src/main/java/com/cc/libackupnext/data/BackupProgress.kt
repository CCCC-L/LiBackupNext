package com.cc.libackupnext.data

import kotlinx.serialization.Serializable

/**
 * 备份 / 恢复的进度事件。
 *
 * 它同时是**跨进程协议**：root 侧按这个结构写进共享内存的进度区，主进程按同样的字段解析
 *
 * @param stage 当前步骤的类型名（apk / obb / user / data / permissions / wifi ...）
 * @param state running / done / skipped / finished / **progress** / **transfer**
 *   `progress` 只更新 [total] / [current]，`transfer` 只更新 [transferred] / [transferSpeed]；两者都不改步骤状态
 * @param files 这一步处理了多少文件
 * @param errors 有多少源文件读不出来（rustic 跳过它们照样出快照，不报就会把「完成」说成假的）
 * @param total 字节进度总量；0 表示 rustic 还没扫出来
 * @param current 字节进度已处理量
 * @param transferred 已过网字节（只有 `transfer` 帧带）：备份是上传量，恢复是下载量
 * @param transferSpeed 传输速度，字节/秒（只有 `transfer` 帧带）
 */
@Serializable
data class BackupProgress(
    val stage: String,
    val state: String,
    val files: Long = 0,
    val errors: Long = 0,
    val reason: String = "",
    val total: Long = 0,
    val current: Long = 0,
    val transferred: Long = 0,
    val transferSpeed: Long = 0
) {
    companion object {
        /** 字节进度帧的状态名。 */
        const val STATE_PROGRESS = "progress"

        /**
         * 传输帧的状态名：远程仓库的上传（备份）/ 下载（恢复）。
         *
         * 单位是过网的加密字节，和 [total] / [current] 的源字节不是同一个量，所以单独一个 state。
         */
        const val STATE_TRANSFER = "transfer"
    }
}
