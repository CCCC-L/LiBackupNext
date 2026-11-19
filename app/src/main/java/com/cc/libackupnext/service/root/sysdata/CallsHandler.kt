package com.cc.libackupnext.service.root.sysdata

import android.content.ContentValues
import android.content.Context
import android.provider.CallLog
import com.cc.libackupnext.data.SnapshotKind
import com.cc.libackupnext.data.json.CallsBackup
import com.cc.libackupnext.service.json.parseJson
import com.cc.libackupnext.service.json.toJsonText

/**
 * 通话记录。
 *
 * 和短信一样，系统里「删掉」只是打标记（`deleted = 1`），provider 不会自动过滤。
 */
internal object CallsHandler : SystemDataHandler {

    override val kind: SnapshotKind = SnapshotKind.CALLS

    /**
     * 「真的通话记录」的筛选条件。
     *
     * - `deleted = 0`：系统里删掉只是打标记，provider 不会自动过滤
     * - `type` 只留平台定义的几种：1 呼入 / 2 呼出 / 3 未接 / 5 拒接 / 6 拦截 / 7 外部接听。
     *   4 是语音信箱（拨号盘也不列），≥ 8 是厂商私有类型 —— ColorOS 用 51 / 20 存
     *   「号码识别 / 骚扰标记」这类影子记录，拨号盘里根本看不到，但 provider 查得到。
     */
    private const val VISIBLE_CALLS = "deleted = 0 AND type IN (1, 2, 3, 5, 6, 7)"

    override fun count(context: Context): Int? =
        SystemDataQuery.count(context, CallLog.Calls.CONTENT_URI, VISIBLE_CALLS)

    override fun export(context: Context): String {
        val calls = mutableListOf<Map<String, String>>()
        // 按 _id 升序，和短信同理，让备份结果可复现
        SystemDataQuery.query(
            context,
            CallLog.Calls.CONTENT_URI,
            VISIBLE_CALLS,
            sortOrder = "${CallLog.Calls._ID} ASC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                calls.add(SystemDataQuery.readRow(cursor, CALL_COLUMNS))
            }
        }
        return CallsBackup(calls = calls).toJsonText()
    }

    override fun restore(context: Context, text: String): RestoreResult =
        restoreCalls(context, text.parseJson<CallsBackup>())

    private fun restoreCalls(context: Context, backup: CallsBackup): RestoreResult {
        val existing = HashSet<String>()
        SystemDataQuery.query(
            context,
            CallLog.Calls.CONTENT_URI,
            projection = arrayOf(
                CallLog.Calls.NUMBER,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION,
                CallLog.Calls.TYPE
            )
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                existing += "${cursor.getString(0)}|${cursor.getString(1)}|" +
                        "${cursor.getString(2)}|${cursor.getString(3)}"
            }
        }

        var skipped = 0
        val rows = ArrayList<ContentValues>()

        for (row in backup.calls) {
            val print = "${row[CallLog.Calls.NUMBER].orEmpty()}|" +
                    "${row[CallLog.Calls.DATE].orEmpty()}|" +
                    "${row[CallLog.Calls.DURATION].orEmpty()}|" +
                    "${row[CallLog.Calls.TYPE].orEmpty()}"
            if (!existing.add(print)) {
                skipped++
                continue
            }
            rows += SystemDataQuery.valuesOf(row)
        }
        if (rows.isEmpty()) return RestoreResult(0, skipped)

        // 一次 bulkInsert 全写完：provider 内部还是逐条 insert，但只走一次 binder，
        // 也不用为每条记录重新借一次 provider
        val added = runCatching {
            ProviderAccess.bulkInsert(context, CallLog.Calls.CONTENT_URI, rows.toTypedArray())
        }.getOrDefault(0).coerceAtLeast(0)

        return RestoreResult(added = added, skipped = skipped, failed = rows.size - added)
    }

    /** 通话记录里值得备份的列。恢复时按同一张表写回。 */
    private val CALL_COLUMNS = listOf(
        CallLog.Calls.NUMBER,
        CallLog.Calls.DATE,
        CallLog.Calls.DURATION,
        CallLog.Calls.TYPE,
        CallLog.Calls.CACHED_NAME,
        CallLog.Calls.CACHED_NUMBER_TYPE,
        CallLog.Calls.CACHED_NUMBER_LABEL,
        CallLog.Calls.GEOCODED_LOCATION,
        CallLog.Calls.COUNTRY_ISO,
        CallLog.Calls.VOICEMAIL_URI,
        CallLog.Calls.IS_READ,
        CallLog.Calls.BLOCK_REASON,
        // @hide，android.jar 里没有常量，但列是真实存在的
        "presentation",
        CallLog.Calls.FEATURES,
        CallLog.Calls.PHONE_ACCOUNT_ID,
        CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME,
        "sub_id",
        CallLog.Calls.POST_DIAL_DIGITS,
        CallLog.Calls.VIA_NUMBER
    )
}
