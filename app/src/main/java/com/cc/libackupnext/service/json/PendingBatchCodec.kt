package com.cc.libackupnext.service.json

import com.cc.libackupnext.data.AppPrefs
import com.cc.libackupnext.data.BackupGroup
import com.cc.libackupnext.data.json.BatchItemDto
import com.cc.libackupnext.data.json.PendingBatchDto
import com.cc.libackupnext.ui.BatchAction
import com.cc.libackupnext.ui.BatchItem
import com.cc.libackupnext.ui.BatchOrigin
import com.cc.libackupnext.ui.HomeTab
import com.cc.libackupnext.ui.PendingBatch
import com.cc.libackupnext.ui.screen.BackupBatchMode

/**
 * 未完成批次的编解码：AppPrefs 只存一个 JSON String，转换放在业务层，
 * UI 只负责持有和渲染 [PendingBatch]。
 *
 * 解码全程容错：坏数据 / 换过仓库 / 本版本不认识的枚举都只丢掉对应项，
 * 兜不住就清掉整份待办，不留脏状态。
 */
internal fun encodePendingBatch(batch: PendingBatch): String = PendingBatchDto(
    repo = AppPrefs.repoPath,
    action = batch.action.name,
    operationId = batch.operationId,
    origin = batch.origin.name,
    batchMode = batch.batchMode?.name.orEmpty(),
    batchTab = batch.batchTab?.name.orEmpty(),
    detailFromBatch = batch.detailFromBatch,
    detailApp = batch.detailApp,
    items = batch.items.map { item ->
        BatchItemDto(
            app = item.app,
            kinds = item.kinds.map { it.name },
            entry = item.entry
        )
    }
).toJsonText()

internal fun loadPendingBatch(): PendingBatch? {
    val raw = AppPrefs.pendingBatchJson
    if (raw.isBlank()) return null
    val batch = runCatching { raw.parseJson<PendingBatchDto>() }.getOrNull()?.toModel()
    if (batch == null) AppPrefs.pendingBatchJson = ""
    return batch
}

private fun PendingBatchDto.toModel(): PendingBatch? {
    if (repo != AppPrefs.repoPath) return null
    val action = runCatching { BatchAction.valueOf(action) }.getOrNull() ?: return null
    val origin = runCatching { BatchOrigin.valueOf(origin) }.getOrNull() ?: return null
    val items = items.mapNotNull { item ->
        runCatching {
            BatchItem(
                app = item.app,
                kinds = item.kinds.mapNotNull { name ->
                    runCatching { BackupGroup.valueOf(name) }.getOrNull()
                }.toSet(),
                entry = item.entry
            )
        }.getOrNull()
    }
    if (items.isEmpty()) return null
    return PendingBatch(
        action = action,
        items = items,
        operationId = operationId,
        origin = origin,
        batchMode = batchMode.takeIf { it.isNotBlank() }
            ?.let { runCatching { BackupBatchMode.valueOf(it) }.getOrNull() },
        batchTab = batchTab.takeIf { it.isNotBlank() }
            ?.let { runCatching { HomeTab.valueOf(it) }.getOrNull() },
        detailApp = detailApp,
        detailFromBatch = detailFromBatch
    )
}
