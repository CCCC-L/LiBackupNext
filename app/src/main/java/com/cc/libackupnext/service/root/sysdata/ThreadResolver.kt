package com.cc.libackupnext.service.root.sysdata

import android.content.ContentProviderOperation
import android.content.Context
import android.net.Uri
import android.util.Log

/**
 * 把「号码 / 收件人集合」解析成会话 id（`threads._id`）。
 *
 * 短信和彩信都挂在会话下，恢复时不能直接用备份里的 `thread_id` ——
 * 那个 id 在本机多半不存在，消息会变成信息应用里看不见的孤儿。

 * 逻辑和系统实现一致：先查 `content://mms-sms/threadID?recipient=`，查不到再建。
 */
internal object ThreadResolver {

    private const val TAG = "ThreadResolver"

    private const val AUTHORITY = "mms-sms"

    private val THREAD_ID_URI: Uri = Uri.parse("content://$AUTHORITY/threadID")
    private val THREADS_URI: Uri = Uri.parse("content://$AUTHORITY/threads")

    /**
     * @param recipients 这条消息的所有参与者。彩信的群发要全部带上，
     *   系统按「参与者集合」算会话，只带一个会算出另一个会话
     * @return 会话 id；建不出来返回 null（调用方按失败计）
     */
    fun threadIdOf(context: Context, recipients: Collection<String>): Long? {
        val numbers = recipients.mapNotNull { it.trim().takeIf(String::isNotEmpty) }.distinct()
        if (numbers.isEmpty()) return null

        return queryThreadId(context, numbers) ?: createThread(context, numbers)
    }

    private fun queryThreadId(context: Context, recipients: List<String>): Long? {
        val uri = THREAD_ID_URI.buildUpon().apply {
            recipients.forEach { appendQueryParameter("recipient", it) }
        }.build()

        return runCatching {
            ProviderAccess.query(context, uri, arrayOf("_id"))?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else null
            }
        }.onFailure { Log.w(TAG, "查会话失败", it) }.getOrNull()
    }

    /**
     * 本机还没有这个会话时建一个。
     *
     * insert 走 `content://mms-sms/threads`，`recipient` 作为 URI 参数传 ——
     * provider 的 insert 只从 URI 上读参与者，不看 ContentValues。
     */
    private fun createThread(context: Context, recipients: List<String>): Long? {
        val uri = THREADS_URI.buildUpon().apply {
            recipients.forEach { appendQueryParameter("recipient", it) }
        }.build()

        val ops = listOf(
            ContentProviderOperation.newInsert(uri)
                .withValue("date", System.currentTimeMillis())
                .build()
        )
        return runCatching {
            ProviderAccess.applyBatch(context, AUTHORITY, ops)
                ?.firstOrNull()?.uri?.lastPathSegment?.toLongOrNull()
        }.onFailure { Log.w(TAG, "建会话失败", it) }.getOrNull()
    }
}
