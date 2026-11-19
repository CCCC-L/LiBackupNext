package com.cc.libackupnext.service.root.sysdata

import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.provider.ContactsContract
import com.cc.libackupnext.data.SnapshotKind
import com.cc.libackupnext.data.json.ContactDataDto
import com.cc.libackupnext.data.json.ContactDto
import com.cc.libackupnext.data.json.ContactsBackup
import com.cc.libackupnext.service.json.parseJson
import com.cc.libackupnext.service.json.toJsonText

/**
 * 联系人：RawContacts 的账号 + 它名下的 Data 行。
 *
 * 头像是 Data 表里的 BLOB（mimetype 为 photo），导出时直接过滤掉 —— 用户确认不备份。
 */
internal object ContactsHandler : SystemDataHandler {

    override val kind: SnapshotKind = SnapshotKind.CONTACTS

    override fun count(context: Context): Int? =
        SystemDataQuery.count(context, ContactsContract.Contacts.CONTENT_URI)

    override fun export(context: Context): String = exportContacts(context).toJsonText()

    override fun restore(context: Context, text: String): RestoreResult =
        restoreContacts(context, text.parseJson<ContactsBackup>())

    // ==================== 导出 ====================

    private fun exportContacts(context: Context): ContactsBackup {
        // raw contact id -> (account_type, account_name)
        val accounts = HashMap<Long, Pair<String?, String?>>()
        SystemDataQuery.query(
            context,
            ContactsContract.RawContacts.CONTENT_URI,
            projection = arrayOf(
                ContactsContract.RawContacts._ID,
                ContactsContract.RawContacts.ACCOUNT_TYPE,
                ContactsContract.RawContacts.ACCOUNT_NAME
            )
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                accounts[cursor.getLong(0)] = cursor.getString(1) to cursor.getString(2)
            }
        }

        val dataColumns = buildList {
            add(ContactsContract.Data.RAW_CONTACT_ID)
            add(ContactsContract.Data.MIMETYPE)
            for (index in 1..15) add("data$index")
        }.toTypedArray()

        val byRawContact = LinkedHashMap<Long, MutableList<ContactDataDto>>()
        SystemDataQuery.query(
            context,
            ContactsContract.Data.CONTENT_URI,
            selection = "${ContactsContract.Data.MIMETYPE} != ?",
            selectionArgs = arrayOf(ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE),
            projection = dataColumns
        )?.use { cursor ->
            val rawIndex = cursor.getColumnIndexOrThrow(ContactsContract.Data.RAW_CONTACT_ID)
            val mimeIndex = cursor.getColumnIndexOrThrow(ContactsContract.Data.MIMETYPE)
            while (cursor.moveToNext()) {
                val rawId = cursor.getLong(rawIndex)
                val mimeType = cursor.getString(mimeIndex) ?: continue

                val fields = buildMap {
                    for (index in 1..15) {
                        val column = "data$index"
                        val columnIndex = cursor.getColumnIndex(column)
                        if (columnIndex < 0 || cursor.isNull(columnIndex)) continue
                        cursor.getString(columnIndex)?.let { put(column, it) }
                    }
                }
                if (fields.isEmpty()) continue

                byRawContact.getOrPut(rawId) { mutableListOf() }.add(
                    ContactDataDto(mimetype = mimeType, fields = fields)
                )
            }
        }

        return ContactsBackup(
            contacts = byRawContact.map { (rawId, data) ->
                val account = accounts[rawId]
                ContactDto(
                    accountType = account?.first,
                    accountName = account?.second,
                    data = data
                )
            }
        )
    }

    // ==================== 恢复 ====================

    /**
     * 同名的联系人已经存在时**不新建**，只把它缺的号码补进去（合并）；
     * 没名字的条目只能按号码判重，号码也重复就跳过。
     */
    private fun restoreContacts(context: Context, backup: ContactsBackup): RestoreResult {
        // 现有联系人：姓名 -> 该联系人已有的号码（只比数字），姓名 -> raw contact id
        val numbersByName = HashMap<String, MutableSet<String>>()
        val rawIdByName = HashMap<String, Long>()
        val allNumbers = HashSet<String>()

        SystemDataQuery.query(
            context,
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.RAW_CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val rawId = cursor.getLong(0)
                val digits = digitsOf(cursor.getString(2))
                if (digits.isNotEmpty()) allNumbers += digits

                val name = normalizeName(cursor.getString(1)) ?: continue
                rawIdByName.putIfAbsent(name, rawId)
                numbersByName.getOrPut(name) { HashSet() }.add(digits)
            }
        }

        var added = 0
        var skipped = 0
        var failed = 0

        for (contact in backup.contacts) {
            val data = contact.data
            val names = nameCandidates(data)
            val phoneRows = phoneRowsOf(data)

            // 1. 同名的已经有了：把缺的号码补进去，不新建一条
            val matchedName = names.firstOrNull { it in rawIdByName }
            val existingRawId = matchedName?.let { rawIdByName[it] }
            if (matchedName != null && existingRawId != null) {
                val known = numbersByName.getOrPut(matchedName) { HashSet() }
                val missing = phoneRows.filter { digitsOf(it["data1"]) !in known }

                if (missing.isEmpty()) {
                    skipped++
                    continue
                }
                // 一批号码一次 applyBatch，别一个号码一次 binder 往返
                val ops = missing.map { row ->
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValues(phoneValues(existingRawId, row))
                        .build()
                }
                val ok = runCatching {
                    ProviderAccess.applyBatch(context, ContactsContract.AUTHORITY, ops)
                }.getOrNull() != null
                if (ok) {
                    missing.forEach { known += digitsOf(it["data1"]) }
                    added++
                } else {
                    failed++
                }
                continue
            }

            // 2. 没名字的条目只能按号码判重
            val firstNumber = digitsOf(phoneRows.firstOrNull()?.get("data1"))
            if (names.isEmpty() && firstNumber.isNotEmpty() && firstNumber in allNumbers) {
                skipped++
                continue
            }

            // 3. 新联系人：建 RawContact + 全部 Data 行
            val ops = ArrayList<ContentProviderOperation>()
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(
                        ContactsContract.RawContacts.ACCOUNT_TYPE,
                        contact.accountType?.takeIf { it.isNotBlank() }
                    )
                    .withValue(
                        ContactsContract.RawContacts.ACCOUNT_NAME,
                        contact.accountName?.takeIf { it.isNotBlank() }
                    )
                    .build()
            )

            for (row in data) {
                val mimeType = row.mimetype
                if (mimeType.isBlank()) continue
                val fields = row.fields

                val op = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, mimeType)
                fields.forEach { (key, value) -> op.withValue(key, value) }
                ops.add(op.build())
            }

            val results = runCatching {
                ProviderAccess.applyBatch(context, ContactsContract.AUTHORITY, ops)
            }.getOrNull()

            if (results == null) {
                failed++
                continue
            }
            added++

            // 同一次恢复里再来一条同名的，也走「合并」而不是再建一条
            val newRawId = results.firstOrNull()?.uri?.lastPathSegment?.toLongOrNull()
            val numbers = phoneRows.map { digitsOf(it["data1"]) }
            allNumbers += numbers
            if (newRawId != null) {
                for (name in names) {
                    rawIdByName[name] = newRawId
                    numbersByName.getOrPut(name) { HashSet() }.addAll(numbers)
                }
            }
        }
        return RestoreResult(added, skipped, failed)
    }

    /** 备份里所有「电话」类型的 Data 行，只留 fields，方便直接取 data1。 */
    private fun phoneRowsOf(data: List<ContactDataDto>): List<Map<String, String>> = buildList {
        for (row in data) {
            if (row.mimetype != ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE) continue
            add(row.fields)
        }
    }

    /** 往已有联系人里补一个号码。 */
    private fun phoneValues(rawContactId: Long, fields: Map<String, String>): ContentValues =
        ContentValues().apply {
            put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
            put(
                ContactsContract.Data.MIMETYPE,
                ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
            )
            fields.forEach { (key, value) -> put(key, value) }
        }

    private fun digitsOf(number: String?): String = number.orEmpty().filter { it.isDigit() }

    /** 比名字时忽略大小写和空白：「同名 测试」和「同名测试」算同一个。 */
    private fun normalizeName(name: String?): String? =
        name?.filterNot { it.isWhitespace() }?.lowercase()?.takeIf { it.isNotEmpty() }

    /**
     * 这个联系人的名字有哪几种写法（都归一化过）。
     *
     * 备份里的 `data1` 是系统算好的显示名，`data2`/`data3` 是名和姓；中文名系统拼成
     * 「姓 名」，英文名拼成「名 姓」—— 只比一种写法会在另一种上匹配不到，
     * 所以三种都算候选。
     */
    private fun nameCandidates(data: List<ContactDataDto>): List<String> {
        var display: String? = null
        var given: String? = null
        var family: String? = null

        for (row in data) {
            if (row.mimetype != ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE) {
                continue
            }
            val fields = row.fields
            display = fields["data1"]?.takeIf { it.isNotBlank() }
            given = fields["data2"]?.takeIf { it.isNotBlank() }
            family = fields["data3"]?.takeIf { it.isNotBlank() }
        }

        return listOfNotNull(
            display,
            listOfNotNull(given, family).joinToString("").takeIf { it.isNotBlank() },
            listOfNotNull(family, given).joinToString("").takeIf { it.isNotBlank() }
        ).mapNotNull { normalizeName(it) }.distinct()
    }
}
