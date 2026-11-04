package com.cc.libackupnext.service.util

import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.IpConfiguration
import android.net.ProxyInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.net.wifi.WifiManagerHidden
import android.os.Build
import android.os.Parcel
import android.provider.CallLog
import android.provider.ContactsContract
import android.util.Log
import com.cc.libackupnext.data.SnapshotKind
import dev.rikka.tools.refine.Refine
import org.json.JSONArray
import org.json.JSONObject

/**
 * 把 [SystemDataExporter] 导出的 JSON 写回系统。
 *
 * 全部走 provider（[ProviderAccess]）/ WifiManager，不碰数据库文件。恢复是**合并**不是覆盖：
 * 每条记录先算一个指纹，已经存在的跳过，重复恢复同一份备份不会翻倍。
 */
internal object SystemDataRestorer {

    private const val TAG = "SystemDataRestorer"

    /**
     * @param added 新写入的条数
     * @param skipped 因为已存在而跳过的条数
     * @param failed 尝试写入但失败的条数（和「已存在」分开，别让用户以为恢复了）
     */
    data class Result(val added: Int, val skipped: Int, val failed: Int = 0) {
        val total: Int get() = added + skipped + failed
    }

    fun restore(context: Context, kind: SnapshotKind, json: JSONObject): Result = try {
        when (kind) {
            SnapshotKind.WIFI -> restoreWifi(context, json)
            SnapshotKind.CONTACTS -> restoreContacts(context, json)
            SnapshotKind.CALLS -> restoreCalls(context, json)
            else -> Result(0, 0)
        }
    } catch (t: Throwable) {
        Log.e(TAG, "恢复 ${kind.key} 失败", t)
        throw t
    }

    // ==================== Wi-Fi ====================

    @Suppress("DEPRECATION")
    private fun restoreWifi(context: Context, root: JSONObject): Result {
        val manager = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return Result(0, 0)
        val hidden = Refine.unsafeCast<WifiManagerHidden>(manager)

        // 同名（SSID）的网络已经存在就跳过 —— addNetwork 不做去重，会多出一条重复的
        val existing = hidden.privilegedConfiguredNetworks.orEmpty()
            .mapNotNull { it.SSID }
            .mapTo(HashSet()) { unquote(it) }

        val networks = root.optJSONArray("networks") ?: JSONArray()
        var added = 0
        var skipped = 0
        var failed = 0

        for (index in 0 until networks.length()) {
            val json = networks.getJSONObject(index)
            val ssid = json.optString("ssid")
            if (ssid.isBlank()) {
                failed++
                continue
            }
            if (!existing.add(ssid)) {
                skipped++
                continue
            }

            val config = buildWifiConfig(json)
            if (config == null) {
                // 企业网要 enterpriseConfig，公开 API 拼不全；数据坏了也一样
                Log.w(TAG, "跳过无法恢复的 Wi-Fi：$ssid")
                failed++
                continue
            }

            // 新增的网络默认是「已保存但禁用」，不禁用的话不会自动连
            val networkId = runCatching { manager.addNetwork(config) }.getOrDefault(-1)
            if (networkId < 0) {
                failed++
                continue
            }
            runCatching { manager.enableNetwork(networkId, false) }
            added++
        }
        return Result(added, skipped, failed)
    }

    /**
     * 按导出的字段重建一个 [WifiConfiguration]；重建不了（企业网）返回 null。
     */
    @Suppress("DEPRECATION")
    private fun buildWifiConfig(json: JSONObject): WifiConfiguration? {
        val keyMgmt = ArrayList<Int>()
        json.optJSONArray("key_mgmt")?.let { array ->
            for (index in 0 until array.length()) keyMgmt += array.optInt(index, -1)
        }
        if (keyMgmt.any { it in ENTERPRISE_KEY_MGMT }) return null

        val config = WifiConfiguration()
        config.SSID = quote(json.optString("ssid"))
        config.hiddenSSID = json.optBoolean("hidden", false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            config.macRandomizationSetting =
                json.optInt("mac_random", WifiConfiguration.RANDOMIZATION_AUTO)
        }

        json.optString("password").takeIf { it.isNotEmpty() }?.let { password ->
            config.preSharedKey = quote(password)
        }

        json.optJSONArray("wep_keys")?.let { keys ->
            val slots = arrayOfNulls<String>(WIFI_WEP_KEY_COUNT)
            for (index in 0 until minOf(keys.length(), WIFI_WEP_KEY_COUNT)) {
                val key = keys.optString(index)
                if (key.isNotEmpty()) slots[index] = quote(key)
            }
            config.wepKeys = slots
            config.wepTxKeyIndex = json.optInt("wep_tx_key", 0)
        }

        keyMgmt.forEach { if (it >= 0) config.allowedKeyManagement.set(it) }

        json.optJSONObject("proxy")?.let { proxy ->
            config.httpProxy = buildProxy(proxy)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            json.optJSONObject("ip")?.let { ip ->
                // 只有 setter，没有公开 getter，不能写属性语法
                buildIpConfiguration(ip)?.let { config.setIpConfiguration(it) }
            }
        }

        return config
    }

    @Suppress("DEPRECATION")
    private fun buildProxy(json: JSONObject): ProxyInfo? {
        val pac = json.optString("pac").takeIf { it.isNotBlank() }
        if (pac != null) return ProxyInfo.buildPacProxy(Uri.parse(pac))

        val host = json.optString("host").takeIf { it.isNotBlank() } ?: return null
        val port = json.optInt("port")
        val exclusions = json.optJSONArray("exclusions")?.let { array ->
            (0 until array.length()).map { array.optString(it) }
        }

        return if (exclusions.isNullOrEmpty()) {
            ProxyInfo.buildDirectProxy(host, port)
        } else {
            ProxyInfo.buildDirectProxy(host, port, exclusions)
        }
    }

    /**
     * 按可读字段拼一个 [IpConfiguration]；拼不出来返回 null。
     */
    private fun buildIpConfiguration(json: JSONObject): IpConfiguration? {
        val dns = json.optJSONArray("dns")?.let { array ->
            (0 until array.length()).map { array.optString(it) }
        }
        // Java 方法不能用具名参数
        return StaticIpFactory.build(
            json.optString("address").takeIf { it.isNotBlank() },
            json.optString("gateway").takeIf { it.isNotBlank() },
            dns,
            json.optString("domains").takeIf { it.isNotBlank() }
        )
    }

    /** `WifiConfiguration` 里的 SSID / 密码都要带引号。 */
    private fun quote(value: String): String = "\"" + value.replace("\"", "\\\"") + "\""

    private fun unquote(value: String?): String =
        value.orEmpty().removeSurrounding("\"").replace("\\\"", "\"")

    /** 这些加密方式要 `enterpriseConfig`，公开 API 拼不出来，恢复时跳过。 */
    private val ENTERPRISE_KEY_MGMT = setOf(
        WifiConfiguration.KeyMgmt.WPA_EAP,
        WifiConfiguration.KeyMgmt.IEEE8021X,
        WifiConfiguration.KeyMgmt.WPA_EAP_SHA256,
        WifiConfiguration.KeyMgmt.SUITE_B_192,
        WifiConfiguration.KeyMgmt.FT_EAP,
        WifiConfiguration.KeyMgmt.OSEN,
        WifiConfiguration.KeyMgmt.WAPI_CERT,
        WifiConfiguration.KeyMgmt.DPP
    )

    private const val WIFI_WEP_KEY_COUNT = 4

    // ==================== 联系人 ====================

    /**
     * 恢复联系人。
     *
     * 同名的联系人已经存在时**不新建**，只把它缺的号码补进去（合并）；
     * 没名字的条目只能按号码判重，号码也重复就跳过。
     */
    private fun restoreContacts(context: Context, root: JSONObject): Result {
        // 现有联系人：姓名 -> 该联系人已有的号码（只比数字），姓名 -> raw contact id
        val numbersByName = HashMap<String, MutableSet<String>>()
        val rawIdByName = HashMap<String, Long>()
        val allNumbers = HashSet<String>()

        ProviderAccess.query(
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

        val contacts = root.optJSONArray("contacts") ?: JSONArray()
        var added = 0
        var skipped = 0
        var failed = 0

        for (index in 0 until contacts.length()) {
            val contact = contacts.getJSONObject(index)
            val data = contact.optJSONArray("data") ?: JSONArray()
            val names = nameCandidates(data)
            val phoneRows = phoneRowsOf(data)

            // 1. 同名的已经有了：把缺的号码补进去，不新建一条
            val matchedName = names.firstOrNull { it in rawIdByName }
            val existingRawId = matchedName?.let { rawIdByName[it] }
            if (matchedName != null && existingRawId != null) {
                val known = numbersByName.getOrPut(matchedName) { HashSet() }
                val missing = phoneRows.filter { digitsOf(it.optString("data1")) !in known }

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
                    missing.forEach { known += digitsOf(it.optString("data1")) }
                    added++
                } else {
                    failed++
                }
                continue
            }

            // 2. 没名字的条目只能按号码判重
            val firstNumber = digitsOf(phoneRows.firstOrNull()?.optString("data1"))
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
                        contact.optString("account_type").takeIf { it.isNotBlank() }
                    )
                    .withValue(
                        ContactsContract.RawContacts.ACCOUNT_NAME,
                        contact.optString("account_name").takeIf { it.isNotBlank() }
                    )
                    .build()
            )

            for (dataIndex in 0 until data.length()) {
                val row = data.getJSONObject(dataIndex)
                val mimeType = row.optString("mimetype")
                if (mimeType.isBlank()) continue
                val fields = row.optJSONObject("fields") ?: continue

                val op = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, mimeType)
                for (key in fields.keys()) {
                    op.withValue(key, fields.optString(key))
                }
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
            val numbers = phoneRows.map { digitsOf(it.optString("data1")) }
            allNumbers += numbers
            if (newRawId != null) {
                for (name in names) {
                    rawIdByName[name] = newRawId
                    numbersByName.getOrPut(name) { HashSet() }.addAll(numbers)
                }
            }
        }
        return Result(added, skipped, failed)
    }

    /** 备份里所有「电话」类型的 Data 行，只留 fields，方便直接取 data1。 */
    private fun phoneRowsOf(data: JSONArray): List<JSONObject> = buildList {
        for (index in 0 until data.length()) {
            val row = data.getJSONObject(index)
            if (row.optString("mimetype") !=
                ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
            ) {
                continue
            }
            row.optJSONObject("fields")?.let { add(it) }
        }
    }

    /** 往已有联系人里补一个号码。 */
    private fun phoneValues(rawContactId: Long, fields: JSONObject): ContentValues =
        ContentValues().apply {
            put(
                ContactsContract.Data.RAW_CONTACT_ID,
                rawContactId
            )
            put(
                ContactsContract.Data.MIMETYPE,
                ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
            )
            for (key in fields.keys()) put(key, fields.optString(key))
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
    private fun nameCandidates(data: JSONArray): List<String> {
        var display: String? = null
        var given: String? = null
        var family: String? = null

        for (index in 0 until data.length()) {
            val row = data.getJSONObject(index)
            if (row.optString("mimetype") !=
                ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
            ) {
                continue
            }
            val fields = row.optJSONObject("fields") ?: continue
            display = fields.optString("data1").takeIf { it.isNotBlank() }
            given = fields.optString("data2").takeIf { it.isNotBlank() }
            family = fields.optString("data3").takeIf { it.isNotBlank() }
        }

        return listOfNotNull(
            display,
            listOfNotNull(given, family).joinToString("").takeIf { it.isNotBlank() },
            listOfNotNull(family, given).joinToString("").takeIf { it.isNotBlank() }
        ).mapNotNull { normalizeName(it) }.distinct()
    }

    // ==================== 通话记录 ====================

    private fun restoreCalls(context: Context, root: JSONObject): Result {
        val existing = HashSet<String>()
        ProviderAccess.query(
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

        val calls = root.optJSONArray("calls") ?: JSONArray()
        var skipped = 0
        val rows = ArrayList<ContentValues>()

        for (index in 0 until calls.length()) {
            val row = calls.getJSONObject(index)
            val print = "${row.optString(CallLog.Calls.NUMBER)}|" +
                    "${row.optString(CallLog.Calls.DATE)}|" +
                    "${row.optString(CallLog.Calls.DURATION)}|" +
                    "${row.optString(CallLog.Calls.TYPE)}"
            if (!existing.add(print)) {
                skipped++
                continue
            }
            rows += toValues(row)
        }
        if (rows.isEmpty()) return Result(0, skipped)

        // 一次 bulkInsert 全写完：provider 内部还是逐条 insert，但只走一次 binder，
        // 也不用为每条记录重新借一次 provider
        val added = runCatching {
            ProviderAccess.bulkInsert(context, CallLog.Calls.CONTENT_URI, rows.toTypedArray())
        }.getOrDefault(0).coerceAtLeast(0)

        return Result(added = added, skipped = skipped, failed = rows.size - added)
    }

    // ==================== 公共 ====================

    private fun toValues(row: JSONObject): ContentValues = ContentValues().apply {
        for (key in row.keys()) {
            put(key, row.optString(key))
        }
    }
}
