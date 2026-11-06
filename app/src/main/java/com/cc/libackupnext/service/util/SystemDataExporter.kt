package com.cc.libackupnext.service.util

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiConfigurationHidden
import android.net.wifi.WifiManager
import android.net.wifi.WifiManagerHidden
import android.os.Build
import android.os.Parcel
import android.provider.BaseColumns
import android.provider.CallLog
import android.provider.ContactsContract
import android.util.Log
import com.cc.libackupnext.util.AppLog
import com.cc.libackupnext.data.SnapshotKind
import dev.rikka.tools.refine.Refine
import org.json.JSONArray
import org.json.JSONObject

/**
 * 把系统数据（Wi-Fi / 联系人 / 通话记录）导出成 JSON。
 *
 * 只读 ContentProvider 和 WifiManager，**不碰数据库文件** —— 厂商把库挪到别处、
 * 换掉文件名都不会出问题。全程在 root 进程里跑：provider 走 [ProviderAccess] 的外部通道，
 * WifiManager 靠 uid 0 过权限检查，不需要任何运行时权限弹窗。
 *
 * 导出的 JSON 会被当成一个普通文件进 rustic 快照（和 permissions.json 同构）。
 */
internal object SystemDataExporter {

    private const val TAG = "SystemDataExporter"

    /**
     * 「真的通话记录」的筛选条件。
     *
     * - `deleted = 0`：系统里删掉只是打标记，provider 不会自动过滤
     * - `type` 只留平台定义的几种：1 呼入 / 2 呼出 / 3 未接 / 5 拒接 / 6 拦截 / 7 外部接听。
     *   4 是语音信箱（拨号盘也不列），≥ 8 是厂商私有类型 —— ColorOS 用 51 / 20 存
     *   「号码识别 / 骚扰标记」这类影子记录，拨号盘里根本看不到，但 provider 查得到。
     */
    private const val VISIBLE_CALLS = "deleted = 0 AND type IN (1, 2, 3, 5, 6, 7)"

    /** 系统数据的类型集合：这几个不是文件快照，备份前要由 root 侧导出。 */
    val SYSTEM_KINDS = listOf(
        SnapshotKind.WIFI,
        SnapshotKind.CONTACTS,
        SnapshotKind.CALLS
    )

    /**
     * 导出成 JSON 文本；失败返回 null（调用方据此把这一步标成「跳过」）。
     */
    fun export(context: Context, kind: SnapshotKind): String? = try {
        when (kind) {
            SnapshotKind.WIFI -> exportWifi(context)
            SnapshotKind.CONTACTS -> exportContacts(context)
            SnapshotKind.CALLS -> exportCalls(context)
            else -> null
        }?.toString()
    } catch (t: Throwable) {
        // 导出失败会被标成「跳过」，属于典型的功能异常，必须留痕
        AppLog.e(TAG, "导出 ${kind.key} 失败（该项会被跳过）", t)
        null
    }

    /** 列表页副标题用的条数；读不到返回 null。 */
    fun count(context: Context, kind: SnapshotKind): Int? = try {
        when (kind) {
            SnapshotKind.WIFI -> wifiConfigs(context).size
            SnapshotKind.CONTACTS -> countOf(context, ContactsContract.Contacts.CONTENT_URI)
            SnapshotKind.CALLS -> countOf(context, CallLog.Calls.CONTENT_URI, VISIBLE_CALLS)
            else -> null
        }
    } catch (t: Throwable) {
        Log.w(TAG, "统计 ${kind.key} 失败", t)
        null
    }

    // ==================== Wi-Fi ====================

    /**
     * 全部已保存的网络。
     *
     * `WifiManager.getConfiguredNetworks()` 只返回本应用创建的网络，拿不到别的；
     * 系统接口 `getPrivilegedConfiguredNetworks()` 返回全部且带明文密码。
     */
    private fun wifiConfigs(context: Context): List<WifiConfiguration> {
        val manager = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return emptyList()
        val hidden = Refine.unsafeCast<WifiManagerHidden>(manager)
        return hidden.privilegedConfiguredNetworks.orEmpty()
    }

    /**
     * 全部已保存的网络，每个存成可读的 JSON。
     *
     * 只有静态 IP 那一段仍然走 Parcel + base64 —— `IpConfiguration` 的构造函数是包内私有的，
     * 公开 API 拼不出来；但它有公开的 `CREATOR`，恢复时能原样读回来。
     */
    private fun exportWifi(context: Context): JSONObject {
        val networks = JSONArray()
        for (config in wifiConfigs(context)) {
            networks.put(exportWifiConfig(config))
        }
        return JSONObject().put("networks", networks)
    }

    @Suppress("DEPRECATION")
    private fun exportWifiConfig(config: WifiConfiguration): JSONObject {
        val json = JSONObject()
            .put("ssid", unquote(config.SSID))
            .put("password", unquote(config.preSharedKey))
            .put("hidden", config.hiddenSSID)
            .put("key_mgmt", keyMgmtOf(config))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            json.put("mac_random", config.macRandomizationSetting)
            staticIpOf(config)?.let { json.put("ip", it) }
        }

        // WEP 的 key 不在 preSharedKey 里，单独存
        val wepKeys = config.wepKeys?.filterNotNull()?.filter { it.isNotEmpty() }
        if (!wepKeys.isNullOrEmpty()) {
            json.put("wep_keys", JSONArray(wepKeys.map { unquote(it) }))
            json.put("wep_tx_key", config.wepTxKeyIndex)
        }

        config.httpProxy?.let { proxy ->
            json.put("proxy", JSONObject().apply {
                proxy.pacFileUrl?.let { put("pac", it.toString()) }
                proxy.host?.let { put("host", it) }
                if (proxy.port > 0) put("port", proxy.port)
                proxy.exclusionList?.takeIf { it.isNotEmpty() }
                    ?.let { put("exclusions", JSONArray(it.toList())) }
            })
        }

        return json
    }

    /**
     * 静态 IP；DHCP 的网络返回 null。
     *
     * `getIpConfiguration` 是隐藏方法，靠 Refine 读；它带的 `IpConfiguration`
     * 只有 `getStaticIpConfiguration` 是公开的。
     */
    private fun staticIpOf(config: WifiConfiguration): JSONObject? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null

        val ipConfig = runCatching {
            Refine.unsafeCast<WifiConfigurationHidden>(config).ipConfiguration
        }.getOrNull() ?: return null

        val static = ipConfig.staticIpConfiguration ?: return null
        return JSONObject().apply {
            static.ipAddress?.let { put("address", it.toString()) }
            static.gateway?.let { put("gateway", it.hostAddress) }
            static.dnsServers?.takeIf { it.isNotEmpty() }
                ?.let { servers -> put("dns", JSONArray(servers.map { it.hostAddress })) }
            static.domains?.takeIf { it.isNotBlank() }?.let { put("domains", it) }
        }
    }

    /** `allowedKeyManagement` 是个 BitSet，存成「哪些位是 true」的数组。 */
    private fun keyMgmtOf(config: WifiConfiguration): JSONArray {
        val bits = config.allowedKeyManagement ?: return JSONArray()
        val array = JSONArray()
        for (index in 0 until bits.size()) {
            if (bits.get(index)) array.put(index)
        }
        return array
    }

    /** `WifiConfiguration` 里的 SSID / 密码是带引号的，存之前去掉。 */
    private fun unquote(value: String?): String =
        value.orEmpty().removeSurrounding("\"").replace("\\\"", "\"")

    // ==================== 联系人 ====================

    /**
     * 联系人导出：RawContacts 的账号 + 它名下的 Data 行。
     *
     * 头像是 Data 表里的 BLOB（mimetype 为 photo），导出时直接过滤掉 —— 用户确认不备份。
     */
    private fun exportContacts(context: Context): JSONObject {
        // raw contact id -> (account_type, account_name)
        val accounts = HashMap<Long, Pair<String?, String?>>()
        query(
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

        val byRawContact = LinkedHashMap<Long, JSONArray>()
        query(
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

                val fields = JSONObject()
                for (index in 1..15) {
                    val column = "data$index"
                    val columnIndex = cursor.getColumnIndex(column)
                    if (columnIndex < 0 || cursor.isNull(columnIndex)) continue
                    fields.put(column, cursor.getString(columnIndex))
                }
                if (fields.length() == 0) continue

                byRawContact.getOrPut(rawId) { JSONArray() }.put(
                    JSONObject().put("mimetype", mimeType).put("fields", fields)
                )
            }
        }

        val contacts = JSONArray()
        for ((rawId, data) in byRawContact) {
            val account = accounts[rawId]
            contacts.put(
                JSONObject()
                    .put("account_type", account?.first ?: JSONObject.NULL)
                    .put("account_name", account?.second ?: JSONObject.NULL)
                    .put("data", data)
            )
        }
        return JSONObject().put("contacts", contacts)
    }

    // ==================== 通话记录 ====================

    private fun exportCalls(context: Context): JSONObject {
        val calls = JSONArray()
        query(context, CallLog.Calls.CONTENT_URI, VISIBLE_CALLS)?.use { cursor ->
            while (cursor.moveToNext()) calls.put(readRow(cursor, CALL_COLUMNS))
        }
        return JSONObject().put("calls", calls)
    }

    // ==================== 公共 ====================

    /**
     * 按列名读一行，只取白名单里**存在且非空**的列。
     *
     * 不用显式 projection：不同版本 / 不同厂商的列不一样，写死列名会因为
     * 某一列不存在直接抛异常；全列查询 + 按名字取就都能容忍。
     */
    private fun readRow(cursor: Cursor, columns: List<String>): JSONObject {
        val row = JSONObject()
        for (name in columns) {
            val index = cursor.getColumnIndex(name)
            if (index < 0 || cursor.isNull(index)) continue
            row.put(name, cursor.getString(index))
        }
        return row
    }

    private fun countOf(context: Context, uri: Uri, selection: String? = null): Int? {
        val cursor = query(context, uri, selection, projection = arrayOf(BaseColumns._ID)) ?: return null
        return cursor.use { it.count }
    }

    /**
     * 带条件的查询；某些版本没有 `deleted` 这一列，就退回不带条件的查询。
     *
     * 具体走 ContentResolver 还是 provider 外部通道由 [ProviderAccess] 按进程决定。
     */
    private fun query(
        context: Context,
        uri: Uri,
        selection: String? = null,
        selectionArgs: Array<String>? = null,
        projection: Array<String>? = null
    ): Cursor? {
        if (selection != null) {
            runCatching {
                ProviderAccess.query(context, uri, projection, selection, selectionArgs)
            }.getOrNull()?.let { return it }
        }
        return runCatching { ProviderAccess.query(context, uri, projection) }.getOrNull()
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
