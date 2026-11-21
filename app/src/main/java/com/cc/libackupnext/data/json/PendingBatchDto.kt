package com.cc.libackupnext.data.json

import com.cc.libackupnext.data.BackupEntry
import com.cc.libackupnext.data.AppInfo
import kotlinx.serialization.Serializable

/**
 * 未完成批次在 SharedPreferences 里的 JSON 快照。
 *
 * 顶层带 repo：换仓库后旧的 operationId / 快照 id 不通用，恢复时直接作废。
 * 枚举一律存名字字符串，解码时再 `valueOf` 并逐个丢弃坏项。
 * 场景不是「老数据」而是「降级安装」：新版本写入的枚举值，旧版本不认识，
 * 按枚举直接解码会让整份待办失效。
 */
@Serializable
data class PendingBatchDto(
    val repo: String = "",
    val action: String = "",
    val operationId: Long = 0,
    val origin: String = "",
    val batchMode: String = "",
    val batchTab: String = "",
    val detailFromBatch: Boolean = false,
    val detailApp: AppInfo? = null,
    /** 发起时顶栏选中的用户；老数据没有 → null。 */
    val userId: Int? = null,
    val items: List<BatchItemDto> = emptyList()
)

@Serializable
data class BatchItemDto(
    val app: AppInfo,
    val kinds: List<String> = emptyList(),
    val entry: BackupEntry? = null
)
