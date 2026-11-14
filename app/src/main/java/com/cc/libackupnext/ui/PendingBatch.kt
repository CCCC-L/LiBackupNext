package com.cc.libackupnext.ui

import com.cc.libackupnext.data.BackupEntry
import com.cc.libackupnext.data.BackupGroup
import com.cc.libackupnext.data.BackupSnapshot
import com.cc.libackupnext.entity.AppInfo
import com.cc.libackupnext.ui.screen.BackupBatchMode

/**
 * 一次操作里的一项：把用户当时勾的内容和选中的那条备份一起冻结。
 *
 * 重试必须原样用这份数据，不能重新读当前勾选 / 当前最新备份 —— 操作过程中
 * `selected`、`deletedKinds`、备份概况都会变，重算就会跑到别的项或别的版本上。
 */
data class BatchItem(
    val app: AppInfo,
    val kinds: Set<BackupGroup>,
    /** 恢复 / 覆盖 / 删除用的那条备份，含版本与快照 id；新建备份为 null。 */
    val entry: BackupEntry? = null
)

/** 未完成批次的操作类型。 */
enum class BatchAction { CREATE, OVERWRITE, RESTORE, DELETE }

/** 从哪个页面发起的：决定「继续未完成」跳回哪里。 */
enum class BatchOrigin { BATCH, DETAIL }

/**
 * 中断 / 失败后剩下的待办。只存数据不存 lambda，跨页面后由目标页面重新构造执行逻辑。
 *
 * @param operationId 一次逻辑操作的标识，备份时写进所有快照的 tag；重试沿用，
 *   主动发起新操作时换新值。
 */
data class PendingBatch(
    val action: BatchAction,
    val items: List<BatchItem>,
    val operationId: Long,
    val origin: BatchOrigin,
    val batchMode: BackupBatchMode? = null,
    val batchTab: HomeTab? = null,
    val detailApp: AppInfo? = null,
    val detailFromBatch: Boolean = false
)

/**
 * AppPrefs 里的 JSON 快照由 service/json/PendingBatchCodec.kt 编解码：
 * 这里只放纯数据类型，UI 不参与序列化。
 */
