package com.cc.libackupnext.service.util

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.BaseColumns
import com.cc.libackupnext.data.SnapshotKind
import java.io.File

/**
 * 一次恢复的结果计数。
 *
 * @param added 新写入的条数
 * @param skipped 因为已存在而跳过的条数
 * @param failed 尝试写入但失败的条数（和「已存在」分开，别让用户以为恢复了）
 */
internal data class RestoreResult(val added: Int, val skipped: Int, val failed: Int = 0) {
    val total: Int get() = added + skipped + failed
}

/**
 * 一种「系统数据」的完整读写能力。
 *
 * 每种数据（Wi-Fi / 联系人 / 通话记录 / 短信 / 彩信）各有一个实现，彼此不知道对方存在：
 * 加一种新类型只要新增一个实现 + 在 [SystemDataHandlers] 注册一行，
 * 门面、调用方、UI 都不用改。
 */
internal interface SystemDataHandler {

    /** 对应的快照类型；默认实现用它给内容文件命名（`<kind>.json`）。 */
    val kind: SnapshotKind

    /** 列表页副标题用的条数；读不到返回 null。 */
    fun count(context: Context): Int?

    /** 导出成 JSON 文本；失败返回 null（调用方据此把这一步标成「跳过」）。 */
    fun export(context: Context): String?

    /**
     * 把内容写进 [dir]，整个目录就是快照的内容。
     *
     * 默认实现只写一个 `<kind>.json`。彩信会覆写它 —— 附件当独立文件复制进去，
     * 用流复制而不是读进内存，多大都不怕。
     */
    fun exportTo(context: Context, dir: File): Boolean {
        val text = export(context) ?: return false
        File(dir, "${kind.key}.json").writeText(text)
        return true
    }

    /** 把 [export] 产出的 JSON 写回系统。 */
    fun restore(context: Context, text: String): RestoreResult

    /**
     * 从快照目录恢复。
     *
     * 默认实现读 `<kind>.json` 再交给 [restore]；需要读附件的类型（彩信）覆写它。
     */
    fun restoreFrom(context: Context, dir: File): RestoreResult =
        restore(context, File(dir, "${kind.key}.json").readText())
}

/**
 * 所有系统数据的注册表。
 *
 * 顺序就是界面上的顺序，也是恢复时的执行顺序（短信先于彩信，会话要先建出来）。
 */
internal object SystemDataHandlers {

    private val all: List<SystemDataHandler> = listOf(
        WifiHandler,
        ContactsHandler,
        CallsHandler,
        SmsHandler,
        MmsHandler
    )

    private val byKind: Map<SnapshotKind, SystemDataHandler> = all.associateBy { it.kind }

    /** 这些不是文件快照，备份前要由 root 侧导出。 */
    val kinds: List<SnapshotKind> = all.map { it.kind }

    operator fun get(kind: SnapshotKind): SystemDataHandler? = byKind[kind]
}

/**
 * 系统数据读写共用的小工具：查询、按列名取一行、计数、转 ContentValues。
 *
 * 具体走 ContentResolver 还是 provider 外部通道由 [ProviderAccess] 按进程决定，
 * 这一层不用关心。
 */
internal object SystemDataQuery {

    /**
     * 带条件的查询；某些版本 / 厂商没有某一列时，退回不带条件的查询。
     *
     * 写死 `deleted = 0` 这类条件在列不存在时会整条 SQL 失败，所以先试带条件的，
     * 失败再退到全量 —— 宁可多读几条（由去重兜住），也不要一条都读不到。
     *
     * @param sortOrder 备份时按 `_id ASC` 排，让结果可复现
     */
    fun query(
        context: Context,
        uri: Uri,
        selection: String? = null,
        selectionArgs: Array<String>? = null,
        projection: Array<String>? = null,
        sortOrder: String? = null
    ): Cursor? {
        if (selection != null || sortOrder != null) {
            runCatching {
                ProviderAccess.query(
                    context, uri, projection, selection, selectionArgs, sortOrder
                )
            }.getOrNull()?.let { return it }
        }
        return runCatching { ProviderAccess.query(context, uri, projection) }.getOrNull()
    }

    /**
     * 按列名读一行，只取白名单里**存在且非空**的列。
     *
     * 不用显式 projection：不同版本 / 不同厂商的列不一样，写死列名会因为
     * 某一列不存在直接抛异常；全列查询 + 按名字取就都能容忍。
     */
    fun readRow(cursor: Cursor, columns: List<String>): Map<String, String> = buildMap {
        for (name in columns) {
            val index = cursor.getColumnIndex(name)
            if (index < 0 || cursor.isNull(index)) continue
            cursor.getString(index)?.let { put(name, it) }
        }
    }

    fun count(context: Context, uri: Uri, selection: String? = null): Int? {
        val cursor = query(context, uri, selection, projection = arrayOf(BaseColumns._ID))
            ?: return null
        return cursor.use { it.count }
    }

    fun valuesOf(row: Map<String, String>): ContentValues = ContentValues().apply {
        row.forEach { (key, value) -> put(key, value) }
    }
}
