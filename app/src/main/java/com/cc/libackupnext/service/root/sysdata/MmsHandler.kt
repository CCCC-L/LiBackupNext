package com.cc.libackupnext.service.root.sysdata

import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.BaseColumns
import android.provider.Telephony
import android.util.Log
import com.cc.libackupnext.data.SnapshotKind
import com.cc.libackupnext.data.json.MmsBackup
import com.cc.libackupnext.data.json.MmsDto
import com.cc.libackupnext.data.json.MmsPartDto
import com.cc.libackupnext.service.json.parseJson
import com.cc.libackupnext.service.json.toJsonText
import com.topjohnwu.superuser.Shell
import java.io.File

/**
 * 彩信：`content://mms` + 它的 `addr`（收件人）和 `part`（正文 / 附件）子表。
 *
 * 四个和短信不一样的地方：
 * - `date` / `date_sent` 是 **Unix 秒**，短信是毫秒。原样存取，不做换算
 * - 附件不在数据库里，`part._data` 指向 provider 私有目录的文件
 * - **附件是快照里的独立文件，不进 JSON**：`parts/` 下按流复制，多大都不占内存
 * - 一条彩信有多个收件人，会话 id 要按**参与者集合**算
 */
internal object MmsHandler : SystemDataHandler {

    private const val TAG = "MmsHandler"

    private const val AUTHORITY = "mms"

    /** 附件在快照目录里的子目录。 */
    private const val PARTS_DIR = "parts"

    private val MMS_URI: Uri = Telephony.Mms.CONTENT_URI
    private val PART_URI: Uri = Telephony.Mms.Part.CONTENT_URI

    override val kind: SnapshotKind = SnapshotKind.MMS

    override fun count(context: Context): Int? = SystemDataQuery.count(context, MMS_URI)

    /**
     * 这个类型没有「一个 JSON 字符串」的形态 —— 附件是独立文件，必须走 [exportTo]。
     * 返回 null 表示「不要用字符串那条路」。
     */
    override fun export(context: Context): String? = null

    override fun restore(context: Context, text: String): RestoreResult =
        restore(context, text.parseJson<MmsBackup>(), dir = null)

    // ==================== 导出 ====================

    override fun exportTo(context: Context, dir: File): Boolean {
        val partsDir = File(dir, PARTS_DIR).apply { mkdirs() }
        val messages = mutableListOf<MmsDto>()

        // 按 _id 升序，和短信同理，让备份结果可复现
        SystemDataQuery.query(
            context,
            MMS_URI,
            sortOrder = "${BaseColumns._ID} ASC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndex(BaseColumns._ID)
            while (cursor.moveToNext()) {
                val id = if (idIndex >= 0) cursor.getLong(idIndex) else continue
                messages += MmsDto(
                    fields = SystemDataQuery.readRow(cursor, MMS_COLUMNS),
                    addrs = queryRows(context, addrUriOf(id), ADDR_COLUMNS),
                    parts = queryParts(context, id, partsDir)
                )
            }
        }

        File(dir, "${kind.key}.json").writeText(MmsBackup(messages = messages).toJsonText())
        return true
    }

    /**
     * 子表查询。**不能**用 [SystemDataQuery.query]：它在带条件的查询失败时会退回全量查询，
     * 对 `mid = ?` 这种条件来说，退回去就是把所有彩信的 part 都塞进这一条里。
     */
    private fun queryRows(
        context: Context,
        uri: Uri,
        columns: List<String>
    ): List<Map<String, String>> {
        val rows = mutableListOf<Map<String, String>>()
        runCatching {
            ProviderAccess.query(context, uri)?.use { cursor ->
                while (cursor.moveToNext()) {
                    rows.add(SystemDataQuery.readRow(cursor, columns))
                }
            }
        }.onFailure { Log.w(TAG, "读 $uri 失败", it) }
        return rows
    }

    private fun queryParts(context: Context, mmsId: Long, partsDir: File): List<MmsPartDto> {
        val parts = mutableListOf<MmsPartDto>()

        runCatching {
            // 走带 id 的 URI，和恢复时用的路径保持一致
            ProviderAccess.query(context, partUriOf(mmsId))?.use { cursor ->
                val dataIndex = cursor.getColumnIndex("_data")
                val seqIndex = cursor.getColumnIndex("seq")
                while (cursor.moveToNext()) {
                    val path = if (dataIndex >= 0 && !cursor.isNull(dataIndex)) {
                        cursor.getString(dataIndex)
                    } else {
                        null
                    }
                    val seq = if (seqIndex >= 0) cursor.getString(seqIndex).orEmpty() else ""
                    parts += MmsPartDto(
                        fields = SystemDataQuery.readRow(cursor, PART_COLUMNS),
                        dataFile = copyAttachment(path, partsDir, mmsId, seq)
                    )
                }
            }
        }.onFailure { Log.w(TAG, "读彩信 $mmsId 的 part 失败", it) }

        return parts
    }

    /**
     * 把附件复制进快照目录，返回它在快照里的相对路径。
     *
     * **流复制，不读进内存** —— 彩信里的视频几百 MB 也不会 OOM。
     * 文件名带 mmsId 和 seq，避免不同彩信的附件重名。
     */
    private fun copyAttachment(
        source: String?,
        partsDir: File,
        mmsId: Long,
        seq: String
    ): String? {
        if (source.isNullOrBlank()) return null

        return runCatching {
            val file = File(source)
            if (!file.isFile) return null

            val name = "mms${mmsId}_${seq}_${file.name}"
            val target = File(partsDir, name)
            file.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            "$PARTS_DIR/$name"
        }.onFailure { Log.w(TAG, "复制附件失败：$source", it) }.getOrNull()
    }

    // ==================== 恢复 ====================

    override fun restoreFrom(context: Context, dir: File): RestoreResult =
        restore(context, File(dir, "${kind.key}.json").readText().parseJson<MmsBackup>(), dir)

    private fun restore(context: Context, backup: MmsBackup, dir: File?): RestoreResult {
        val existing = HashSet<String>()
        SystemDataQuery.query(
            context,
            MMS_URI,
            projection = arrayOf("date", "msg_box", "sub", "m_type", "m_size")
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                existing += fingerprint(
                    cursor.getString(0),
                    cursor.getString(1),
                    cursor.getString(2),
                    cursor.getString(3),
                    cursor.getString(4)
                )
            }
        }

        var added = 0
        var skipped = 0
        var failed = 0

        for (mms in backup.messages) {
            val print = fingerprint(
                mms.fields["date"],
                mms.fields["msg_box"],
                mms.fields["sub"],
                mms.fields["m_type"],
                mms.fields["m_size"]
            )
            if (!existing.add(print)) {
                skipped++
                continue
            }
            if (insertOne(context, mms, dir)) added++ else failed++
        }

        return RestoreResult(added, skipped, failed)
    }

    /**
     * 写一条彩信。
     *
     * 必须分两步：`addr` / `part` 的 URI 里带 mms 的 id，所以得先拿到新行 id 才能插子表。
     * 子表插入失败时把主行删掉 —— 否则会留下一条没有任何内容的空彩信，
     * 在信息应用里看得见、点进去是空白，比失败更糟。
     */
    private fun insertOne(context: Context, mms: MmsDto, dir: File?): Boolean {
        val values = SystemDataQuery.valuesOf(mms.fields)

        // 会话要按全部参与者算，只带发件人会算出另一个会话
        val recipients = mms.addrs.mapNotNull { it["address"] }
        ThreadResolver.threadIdOf(context, recipients)?.let { threadId ->
            values.put("thread_id", threadId)
        }

        val mmsId = runCatching {
            ProviderAccess.applyBatch(
                context,
                AUTHORITY,
                listOf(
                    ContentProviderOperation.newInsert(MMS_URI).withValues(values).build()
                )
            )?.firstOrNull()?.uri?.lastPathSegment?.toLongOrNull()
        }.onFailure { Log.w(TAG, "插入彩信失败", it) }.getOrNull() ?: return false

        val ok = runCatching {
            if (mms.addrs.isNotEmpty()) {
                ProviderAccess.applyBatch(
                    context,
                    AUTHORITY,
                    mms.addrs.map { addr ->
                        ContentProviderOperation.newInsert(addrUriOf(mmsId))
                            .withValues(SystemDataQuery.valuesOf(addr))
                            .build()
                    }
                )
            }

            if (mms.parts.isNotEmpty()) {
                // 子表用带 id 的 URI：顶层 `content://mms/part` 只支持 query，
                // insert 会被 provider 直接拒掉（而且不报错，只是没有行落库）
                val results = ProviderAccess.applyBatch(
                    context,
                    AUTHORITY,
                    mms.parts.map { part ->
                        ContentProviderOperation.newInsert(partUriOf(mmsId))
                            .withValues(SystemDataQuery.valuesOf(part.fields))
                            .build()
                    }
                )

                // 附件：provider 插入 part 时会自己建好空文件并把路径写进 _data，
                // 所以这里只要把内容写进那个路径 —— 归属和 SELinux 上下文都是现成的
                val partsRoot = dir
                if (partsRoot != null) {
                    results?.forEachIndexed { index, result ->
                        val dataFile = mms.parts.getOrNull(index)?.dataFile
                            ?: return@forEachIndexed
                        val partId = result.uri?.lastPathSegment?.toLongOrNull()
                            ?: return@forEachIndexed
                        copyPartData(context, partId, File(partsRoot, dataFile))
                    }
                }
            }
            true
        }.onFailure { Log.w(TAG, "插入彩信 $mmsId 的 addr / part 失败", it) }.getOrDefault(false)

        if (!ok) {
            // 主行会连带删掉它的 addr / part
            runCatching {
                ProviderAccess.applyBatch(
                    context,
                    AUTHORITY,
                    listOf(
                        ContentProviderOperation.newDelete(
                            ContentUris.withAppendedId(MMS_URI, mmsId)
                        ).build()
                    )
                )
            }.onFailure { Log.w(TAG, "回滚彩信 $mmsId 失败", it) }
        }

        return ok
    }

    /** 把快照里的附件文件复制进 provider 为这个 part 建好的文件（流复制，不占内存）。 */
    private fun copyPartData(context: Context, partId: Long, source: File) {
        runCatching {
            if (!source.isFile) {
                Log.w(TAG, "附件文件不存在：${source.absolutePath}")
                return
            }
            val target = targetFileOf(context, partId) ?: return
            source.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            fixupPartFile(target)
        }.onFailure { Log.w(TAG, "写 part $partId 的附件失败", it) }
    }

    /** part 在 provider 私有目录里的落盘位置（插入 part 时 provider 已经建好）。 */
    private fun targetFileOf(context: Context, partId: Long): File? {
        val path = ProviderAccess.query(
            context,
            ContentUris.withAppendedId(PART_URI, partId),
            arrayOf("_data")
        )?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
        if (path.isNullOrBlank()) {
            Log.w(TAG, "part $partId 没有 _data 路径，附件跳过")
            return null
        }
        return File(path)
    }

    /** 归属 / 权限 / SELinux 上下文对齐父目录。 */
    private fun fixupPartFile(file: File) {
        val parent = file.parentFile ?: return
        val path = file.absolutePath
        val dir = parent.absolutePath
        // toybox 的 chown 不认 --reference，用 stat 取父目录的 uid:gid
        val script = "chown \$(stat -c %u:%g \"$dir\") \"$path\"; " +
                "chmod 600 \"$path\"; " +
                "chcon \"\$(ls -Zd \"$dir\" | grep -o 'u:object_r:[^ ]*')\" \"$path\""
        runCatching { Shell.cmd(script).exec() }
            .onFailure { Log.w(TAG, "修正附件归属失败：$path", it) }
    }

    private fun addrUriOf(mmsId: Long): Uri =
        ContentUris.withAppendedId(MMS_URI, mmsId).buildUpon().appendPath("addr").build()

    private fun partUriOf(mmsId: Long): Uri =
        ContentUris.withAppendedId(MMS_URI, mmsId).buildUpon().appendPath("part").build()

    /**
     * 判重指纹：`date | msg_box | sub | m_type | m_size`。
     *
     * 彩信的 `date` 只有**秒**精度（短信是毫秒），而 `sub`（主题）普通彩信基本为空、
     * `msg_box` 恒为 1、`m_type` 恒为 132 —— 前四项实际上退化成一个 date，同一秒收到
     * 的两条彩信会被判成同一条。加上 `m_size`（主表列，不用额外查询）把「同秒同大小」
     * 也纳入判断。
     *
     * 仍然不完美：同秒、同大小、同主题的两条彩信还是会撞。要彻底区分得读 `addr`
     * 子表拿发件人集合，那是每条 mms 一次额外查询，彩信量大时不划算。
     */
    private fun fingerprint(
        date: String?,
        msgBox: String?,
        subject: String?,
        type: String?,
        size: String?
    ): String = "${date.orEmpty()}|${msgBox.orEmpty()}|${subject.orEmpty()}|" +
            "${type.orEmpty()}|${size.orEmpty()}"

    /**
     * mms 表里值得备份的标准列（AOSP 定义的，厂商私有列一律不取）。
     *
     * 写字符串而不是 `Telephony.Mms.*` 常量：其中一部分是 @hide 或者跨版本改名，
     * 用常量会让「某台机器上编译不过 / 运行时空值」的风险变成编译期错误。
     */
    private val MMS_COLUMNS = listOf(
        "date", "date_sent", "msg_box", "read", "seen",
        "sub", "sub_cs", "ct_t", "ct_l", "exp", "m_cls", "m_type", "v", "m_size",
        "pri", "rr", "rpt_a", "resp_st", "st", "tr_id", "retr_st", "retr_txt",
        "retr_txt_cs", "read_status", "ct_cls", "resp_txt", "d_tm", "d_rpt",
        "locked", "sub_id", "text_only", "m_id"
    )

    /** addr 子表：谁发的、发给谁。 */
    private val ADDR_COLUMNS = listOf("address", "type", "charset")

    /** part 子表：正文和附件的元数据（`_data` 单独处理，不进 JSON）。 */
    private val PART_COLUMNS = listOf(
        "seq", "ct", "name", "chset", "cd", "fn", "cid", "cl", "ctt_s", "ctt_t", "text"
    )
}
