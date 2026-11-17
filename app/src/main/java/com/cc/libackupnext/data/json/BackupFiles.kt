@file:OptIn(ExperimentalSerializationApi::class)

package com.cc.libackupnext.data.json

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 作为普通文件进 rustic 快照的 JSON：permissions.json / wifi.json / contacts.json / calls.json。
 *
 * 这些文件会躺在老仓库里等以后恢复，字段名和层级必须保持原样。
 * 动态列（appops 的 op code、联系人的 data1..data15、通话记录的任意列名）用 Map 表达，
 * 不硬造实体类。
 */

// ==================== permissions.json（AppState） ====================

@Serializable
data class AppStateFile(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val packageName: String = "",
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val userId: Int = 0,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val granted: List<String> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val requested: List<String> = emptyList(),
    val appops: AppOpsFile? = null,
    /** 老快照没有这个字段，不能当成 false —— 那会把白名单删掉。 */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val batteryWhitelist: Boolean? = null,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val standbyBucket: Int = -1
)

/** 包级 / uid 级的 op code -> 模式；JSON 里的 key 是数字字符串。 */
@Serializable
data class AppOpsFile(
    @SerialName("package") val packageModes: Map<String, Int> = emptyMap(),
    val uid: Map<String, Int> = emptyMap()
)

// ==================== wifi.json ====================

@Serializable
data class WifiBackup(val networks: List<WifiNetworkDto>)

@Serializable
data class WifiNetworkDto(
    val ssid: String,
    val password: String,
    val hidden: Boolean,
    @SerialName("key_mgmt") val keyMgmt: List<Int>,
    @SerialName("mac_random") val macRandom: Int? = null,
    val ip: StaticIpDto? = null,
    @SerialName("wep_keys") val wepKeys: List<String>? = null,
    @SerialName("wep_tx_key") val wepTxKey: Int? = null,
    val proxy: ProxyDto? = null
)

@Serializable
data class StaticIpDto(
    val address: String? = null,
    val gateway: String? = null,
    val dns: List<String>? = null,
    val domains: String? = null
)

@Serializable
data class ProxyDto(
    val pac: String? = null,
    val host: String? = null,
    val port: Int? = null,
    val exclusions: List<String>? = null
)

// ==================== contacts.json ====================

@Serializable
data class ContactsBackup(val contacts: List<ContactDto>)

@Serializable
data class ContactDto(
    @SerialName("account_type") val accountType: String? = null,
    @SerialName("account_name") val accountName: String? = null,
    val data: List<ContactDataDto>
)

/** fields 是 ContentValues 的任意列（data1..data15），key 不能写死。 */
@Serializable
data class ContactDataDto(
    val mimetype: String,
    val fields: Map<String, String>
)

// ==================== calls.json ====================

/** 每行都是「列名 -> 值」的动态 Map：不同版本 / 厂商的列不一样。 */
@Serializable
data class CallsBackup(val calls: List<Map<String, String>>)

// ==================== sms.json ====================

/**
 * 短信：每行都是「标准列名 -> 值」。
 *
 * 只收 AOSP 定义的标准列，厂商私有列（oplus_* / rcs_* / bubble 这些）一个都不存 ——
 * 它们回写时会被 provider 拒绝，或者写进去让信息应用显示错乱。
 */
@Serializable
data class SmsBackup(val messages: List<Map<String, String>>)

// ==================== mms.json ====================

/** 彩信：一条 mms 行 + 它的地址表和 part 表。 */
@Serializable
data class MmsBackup(val messages: List<MmsDto>)

@Serializable
data class MmsDto(
    /** mms 表的字段（同样只收标准列） */
    val fields: Map<String, String>,
    /** content://mms/<id>/addr 的行：收件人 / 发件人 / 抄送 */
    val addrs: List<Map<String, String>> = emptyList(),
    val parts: List<MmsPartDto> = emptyList()
)

/**
 * 一个 part。
 *
 * @param fields part 表的字段；`_data` 不存 —— 它指向 provider 的私有目录，
 *   恢复时要重新生成
 * @param dataFile 附件在快照目录里的相对路径（`parts/xxx`）。附件是**独立文件**。
 */
@Serializable
data class MmsPartDto(
    val fields: Map<String, String>,
    val dataFile: String? = null
)
