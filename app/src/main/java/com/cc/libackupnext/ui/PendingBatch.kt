package com.cc.libackupnext.ui

import com.cc.libackupnext.data.AppPrefs
import com.cc.libackupnext.data.BackupEntry
import com.cc.libackupnext.data.BackupGroup
import com.cc.libackupnext.data.BackupSnapshot
import com.cc.libackupnext.entity.AppInfo
import com.cc.libackupnext.ui.screen.BackupBatchMode
import org.json.JSONArray
import org.json.JSONObject

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

// ==================== 未完成批次的编解码 ====================
// AppPrefs 只存一个 JSON String，转换留在这里，避免 data 层反过来依赖 ui 层。
// 全部字段都是简单类型 / 枚举名 / 列表，照 SavedRepo 的手写 JSONObject 风格来。

private fun encodeAppInfo(app: AppInfo): JSONObject = JSONObject().apply {
    put("name", app.name)
    put("packageName", app.packageName)
    put("version", app.version)
    put("versionCode", app.versionCode)
    put("installTime", app.installTime)
    put("updateTime", app.updateTime)
    put("userId", app.userId)
    put("isSystem", app.isSystem)
    put("isFrozen", app.isFrozen)
    put("onlyInBackup", app.onlyInBackup)
}

private fun decodeAppInfo(o: JSONObject): AppInfo = AppInfo(
    name = o.optString("name"),
    packageName = o.optString("packageName"),
    version = o.optString("version"),
    versionCode = o.optLong("versionCode"),
    installTime = o.optLong("installTime"),
    updateTime = o.optLong("updateTime"),
    userId = o.optInt("userId"),
    isSystem = o.optBoolean("isSystem"),
    isFrozen = o.optBoolean("isFrozen"),
    onlyInBackup = o.optBoolean("onlyInBackup")
)

private fun encodeSnapshot(s: BackupSnapshot): JSONObject = JSONObject().apply {
    put("id", s.id)
    put("label", s.label)
    put("tags", s.tags)
    put("time", s.time)
    put("size", s.size)
    // put(key, null) 会直接删掉这个 key，可空字段只在有值时写
    s.description?.let { put("description", it) }
}

private fun decodeSnapshot(o: JSONObject): BackupSnapshot = BackupSnapshot(
    id = o.optString("id"),
    label = o.optString("label"),
    tags = o.optString("tags"),
    time = o.optLong("time"),
    size = o.optLong("size"),
    description = o.optString("description").takeIf { it.isNotBlank() }
)

private fun encodeEntry(entry: BackupEntry): JSONObject = JSONObject().apply {
    put("title", entry.title)
    put("time", entry.time)
    entry.description?.let { put("description", it) }
    entry.version?.let { put("version", it) }
    entry.userName?.let { put("userName", it) }
    put("snapshots", JSONArray().apply {
        entry.snapshots.forEach { put(encodeSnapshot(it)) }
    })
}

private fun decodeEntry(o: JSONObject): BackupEntry {
    val array = o.optJSONArray("snapshots") ?: JSONArray()
    return BackupEntry(
        title = o.optString("title"),
        time = o.optLong("time"),
        description = o.optString("description").takeIf { it.isNotBlank() },
        version = o.optString("version").takeIf { it.isNotBlank() },
        userName = o.optString("userName").takeIf { it.isNotBlank() },
        snapshots = (0 until array.length()).map { decodeSnapshot(array.getJSONObject(it)) }
    )
}

private fun encodeBatchItem(item: BatchItem): JSONObject = JSONObject().apply {
    put("app", encodeAppInfo(item.app))
    put("kinds", JSONArray().apply { item.kinds.forEach { put(it.name) } })
    item.entry?.let { put("entry", encodeEntry(it)) }
}

private fun decodeBatchItem(o: JSONObject): BatchItem {
    val kindsArray = o.optJSONArray("kinds") ?: JSONArray()
    return BatchItem(
        app = decodeAppInfo(o.getJSONObject("app")),
        kinds = (0 until kindsArray.length()).mapNotNull { i ->
            runCatching { BackupGroup.valueOf(kindsArray.optString(i)) }.getOrNull()
        }.toSet(),
        entry = o.optJSONObject("entry")?.let { decodeEntry(it) }
    )
}

/** 顶层带 repo：换仓库后旧的 operationId / 快照 id 不通用，恢复时直接作废。 */
internal fun encodePendingBatch(batch: PendingBatch): String = JSONObject().apply {
    put("repo", AppPrefs.repoPath)
    put("action", batch.action.name)
    put("operationId", batch.operationId)
    put("origin", batch.origin.name)
    put("batchMode", batch.batchMode?.name.orEmpty())
    put("batchTab", batch.batchTab?.name.orEmpty())
    put("detailFromBatch", batch.detailFromBatch)
    batch.detailApp?.let { put("detailApp", encodeAppInfo(it)) }
    put("items", JSONArray().apply { batch.items.forEach { put(encodeBatchItem(it)) } })
}.toString()

private fun decodePendingBatch(o: JSONObject): PendingBatch? {
    if (o.optString("repo") != AppPrefs.repoPath) return null
    val action = runCatching { BatchAction.valueOf(o.optString("action")) }.getOrNull()
        ?: return null
    val origin = runCatching { BatchOrigin.valueOf(o.optString("origin")) }.getOrNull()
        ?: return null
    val array = o.optJSONArray("items") ?: return null
    val items = (0 until array.length()).mapNotNull { i ->
        runCatching { decodeBatchItem(array.getJSONObject(i)) }.getOrNull()
    }
    if (items.isEmpty()) return null
    return PendingBatch(
        action = action,
        items = items,
        operationId = o.optLong("operationId"),
        origin = origin,
        batchMode = o.optString("batchMode").takeIf { it.isNotBlank() }
            ?.let { runCatching { BackupBatchMode.valueOf(it) }.getOrNull() },
        batchTab = o.optString("batchTab").takeIf { it.isNotBlank() }
            ?.let { runCatching { HomeTab.valueOf(it) }.getOrNull() },
        detailApp = o.optJSONObject("detailApp")?.let { decodeAppInfo(it) },
        detailFromBatch = o.optBoolean("detailFromBatch", false)
    )
}

/** ViewModel 构造时读一次；坏数据 / 换过仓库都直接清掉，不留脏待办。 */
internal fun loadPendingBatch(): PendingBatch? {
    val raw = AppPrefs.pendingBatchJson
    if (raw.isBlank()) return null
    val batch = runCatching { decodePendingBatch(JSONObject(raw)) }.getOrNull()
    if (batch == null) AppPrefs.pendingBatchJson = ""
    return batch
}
