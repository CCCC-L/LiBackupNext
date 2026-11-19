package com.cc.libackupnext.service.root.sysdata

import android.content.ContentValues
import android.content.Context
import android.provider.Telephony
import android.util.Log
import com.cc.libackupnext.data.SnapshotKind
import com.cc.libackupnext.data.json.SmsBackup
import com.cc.libackupnext.service.json.parseJson
import com.cc.libackupnext.service.json.toJsonText

/**
 * 短信：`content://sms`。
 *
 * 只取 AOSP 定义的标准列。这台机器上 sms 表有 80 多列，一半是 `oplus_*` / `rcs_*` /
 * `bubble` 这类厂商私有列 —— 回写时会被 provider 拒绝，或者写进去让信息应用显示错乱，
 * 所以一个都不要。
 *
 * 和通话记录一样，系统里「删掉」只是打标记（`deleted = 1`），provider 不会自动过滤。
 */
internal object SmsHandler : SystemDataHandler {

    private const val TAG = "SmsHandler"

    private const val VISIBLE_SMS = "deleted = 0"

    override val kind: SnapshotKind = SnapshotKind.SMS

    override fun count(context: Context): Int? =
        SystemDataQuery.count(context, Telephony.Sms.CONTENT_URI, VISIBLE_SMS)

    override fun export(context: Context): String {
        val messages = mutableListOf<Map<String, String>>()
        // 按 _id 升序，让备份结果可复现
        SystemDataQuery.query(
            context,
            Telephony.Sms.CONTENT_URI,
            VISIBLE_SMS,
            sortOrder = "${Telephony.Sms._ID} ASC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                messages.add(SystemDataQuery.readRow(cursor, SMS_COLUMNS))
            }
        }
        return SmsBackup(messages = messages).toJsonText()
    }

    override fun restore(context: Context, text: String): RestoreResult {
        val backup = text.parseJson<SmsBackup>()

        // 已有消息的指纹：重复恢复同一份备份时不再写一遍
        val existing = HashSet<String>()
        SystemDataQuery.query(
            context,
            Telephony.Sms.CONTENT_URI,
            projection = arrayOf(
                Telephony.Sms.ADDRESS,
                Telephony.Sms.DATE,
                Telephony.Sms.TYPE,
                Telephony.Sms.BODY
            )
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                existing += fingerprint(
                    cursor.getString(0),
                    cursor.getString(1),
                    cursor.getString(2),
                    cursor.getString(3)
                )
            }
        }

        var skipped = 0
        val rows = ArrayList<ContentValues>()
        // 同一个号码在一次恢复里只查一次会话
        val threadIds = HashMap<String, Long?>()

        for (row in backup.messages) {
            val address = row[Telephony.Sms.ADDRESS].orEmpty()
            val print = fingerprint(
                address,
                row[Telephony.Sms.DATE],
                row[Telephony.Sms.TYPE],
                row[Telephony.Sms.BODY]
            )
            if (!existing.add(print)) {
                skipped++
                continue
            }

            val values = SystemDataQuery.valuesOf(row)
            val threadId = threadIds.getOrPut(address) {
                ThreadResolver.threadIdOf(context, listOf(address))
            }
            if (threadId != null) {
                values.put(Telephony.Sms.THREAD_ID, threadId)
            } else {
                Log.w(TAG, "找不到会话，消息会落在默认会话里：$address")
            }
            rows += values
        }

        if (rows.isEmpty()) return RestoreResult(0, skipped)

        // 一次 bulkInsert 全写完：provider 内部还是逐条 insert，但只走一次 binder
        val added = runCatching {
            ProviderAccess.bulkInsert(context, Telephony.Sms.CONTENT_URI, rows.toTypedArray())
        }.getOrDefault(0).coerceAtLeast(0)

        return RestoreResult(added = added, skipped = skipped, failed = rows.size - added)
    }

    /**
     * 判重用的指纹。
     * 号码 + 时间 + 类型 + 正文
     */
    private fun fingerprint(
        address: String?,
        date: String?,
        type: String?,
        body: String?
    ): String = "${address.orEmpty()}|${date.orEmpty()}|${type.orEmpty()}|${body.orEmpty()}"

    /** 短信里值得备份的标准列。恢复时按同一张表写回。 */
    private val SMS_COLUMNS = listOf(
        Telephony.Sms.ADDRESS,
        Telephony.Sms.DATE,
        Telephony.Sms.DATE_SENT,
        Telephony.Sms.TYPE,
        Telephony.Sms.READ,
        Telephony.Sms.SEEN,
        Telephony.Sms.STATUS,
        Telephony.Sms.PROTOCOL,
        Telephony.Sms.REPLY_PATH_PRESENT,
        Telephony.Sms.SUBJECT,
        Telephony.Sms.BODY,
        Telephony.Sms.SERVICE_CENTER,
        Telephony.Sms.ERROR_CODE,
        Telephony.Sms.LOCKED,
        // @hide，android.jar 里没有常量，但列是真实存在的
        "sub_id"
    )
}
