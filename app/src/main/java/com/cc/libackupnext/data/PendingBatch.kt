package com.cc.libackupnext.data

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
 * @param userId 发起时顶栏选中的用户。续跑前要先切回它 —— 执行用的是 [items] 里
 *   冻结的条目（和当前顶栏无关），但界面得和实际对齐，否则用户看到的是另一个用户的行。
 */
data class PendingBatch(
    val action: BatchAction,
    val items: List<BatchItem>,
    val operationId: Long,
    val origin: BatchOrigin,
    val batchMode: BackupBatchMode? = null,
    /** 发起时在哪个 tab（[com.cc.libackupnext.ui.HomeTab] 的 name）；UI 自己转回枚举。 */
    val batchTab: String? = null,
    val detailApp: AppInfo? = null,
    val detailFromBatch: Boolean = false,
    /** 老数据没有这个字段 → null，续跑时不动当前用户。 */
    val userId: Int? = null
)
