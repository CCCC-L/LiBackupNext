package com.cc.libackupnext.service.json

import com.cc.libackupnext.data.prefs.PendingBatchStore
import com.cc.libackupnext.data.BackupBatchMode
import com.cc.libackupnext.data.BackupGroup
import com.cc.libackupnext.data.json.BatchItemDto
import com.cc.libackupnext.data.json.PendingBatchDto
import com.cc.libackupnext.data.BatchAction
import com.cc.libackupnext.data.BatchItem
import com.cc.libackupnext.data.BatchOrigin
import com.cc.libackupnext.data.PendingBatch

/**
 * 未完成批次的编解码：PendingBatchStore 只存一个 JSON String，转换放在业务层，
 * UI 只负责持有和渲染 [PendingBatch]。
 *
 * 解码全程容错：坏数据 / 换过仓库 / 本版本不认识的枚举都只丢掉对应项，
 * 兜不住就清掉整份待办，不留脏状态。
 */
internal fun encodePendingBatch(batch: PendingBatch, repoLocation: String): String = PendingBatchDto(
    repo = repoLocation,
    action = batch.action.name,
    operationId = batch.operationId,
    origin = batch.origin.name,
    batchMode = batch.batchMode?.name.orEmpty(),
    batchTab = batch.batchTab.orEmpty(),
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

internal fun loadPendingBatch(
    store: PendingBatchStore,
    repoLocation: String,
): PendingBatch? {
    val raw = store.json
    if (raw.isBlank()) return null
    val batch = runCatching { raw.parseJson<PendingBatchDto>() }.getOrNull()
        ?.toModel(repoLocation)
    if (batch == null) store.json = ""
    return batch
}

private fun PendingBatchDto.toModel(repoLocation: String): PendingBatch? {
    if (repo != repoLocation) return null
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
        batchTab = batchTab.takeIf { it.isNotBlank() },
        detailApp = detailApp,
        detailFromBatch = detailFromBatch
    )
}
