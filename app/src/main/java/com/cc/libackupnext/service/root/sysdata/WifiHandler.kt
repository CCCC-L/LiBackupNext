package com.cc.libackupnext.service.root.sysdata

import android.content.Context
import android.net.IpConfiguration
import android.net.LinkAddress
import android.net.ProxyInfo
import android.net.Uri
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiConfigurationHidden
import android.net.wifi.WifiManager
import android.net.wifi.WifiManagerHidden
import android.os.Build
import android.util.Log
import com.cc.libackupnext.data.SnapshotKind
import com.cc.libackupnext.data.json.ProxyDto
import com.cc.libackupnext.data.json.StaticIpDto
import com.cc.libackupnext.data.json.WifiBackupFile
import com.cc.libackupnext.data.json.WifiNetworkDto
import com.cc.libackupnext.service.json.parseJson
import com.cc.libackupnext.service.json.toJsonText
import dev.rikka.tools.refine.Refine
import com.cc.libackupnext.service.root.sysdata.StaticIpFactory
import java.net.InetAddress
import java.util.BitSet

/**
 * 已保存的 Wi-Fi（含明文密码）。
 *
 * `WifiManager.getConfiguredNetworks()` 只返回本应用创建的网络，拿不到别的；
 * 系统接口 `getPrivilegedConfiguredNetworks()` 返回全部且带明文密码。
 */
internal object WifiHandler : SystemDataHandler {

    private const val TAG = "WifiHandler"

    override val kind: SnapshotKind = SnapshotKind.WIFI

    override fun count(context: Context): Int? = runCatching {
        wifiConfigs(context).size
    }.onFailure { Log.w(TAG, "统计 Wi-Fi 失败", it) }.getOrNull()

    override fun export(context: Context): String =
        WifiBackupFile(networks = wifiConfigs(context).map { exportWifiConfig(it) }).toJsonText()

    override fun restore(context: Context, text: String): RestoreResult =
        restoreWifi(context, text.parseJson<WifiBackupFile>())

    // ==================== 导出 ====================

    private fun wifiConfigs(context: Context): List<WifiConfiguration> {
        val manager = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return emptyList()
        val hidden = Refine.unsafeCast<WifiManagerHidden>(manager)
        return hidden.privilegedConfiguredNetworks.orEmpty()
    }

    /**
     * 每个网络存成可读的 JSON。
     *
     * 静态 IP 用 [com.cc.libackupnext.service.root.sysdata.StaticIpFactory] 通过 hidden API
     * 反射构造 `IpConfiguration` —— 公开 API 拼不出来（`setIpConfiguration` 到 33 才开放），
     * 但反射能直接 new 出 `StaticIpConfigurationHidden` / `IpConfigurationHidden`。
     * 不走 Parcel，也不走 base64。
     */
    @Suppress("DEPRECATION")
    private fun exportWifiConfig(config: WifiConfiguration): WifiNetworkDto {
        var network = WifiNetworkDto(
            ssid = unquote(config.SSID),
            password = unquote(config.preSharedKey),
            hidden = config.hiddenSSID,
            keyMgmt = keyMgmtOf(config)
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            network = network.copy(
                macRandom = config.macRandomizationSetting,
                ip = staticIpOf(config)
            )
        }

        // WEP 的 key 不在 preSharedKey 里，单独存
        val wepKeys = config.wepKeys?.filterNotNull()?.filter { it.isNotEmpty() }
        if (!wepKeys.isNullOrEmpty()) {
            network = network.copy(
                wepKeys = wepKeys.map { unquote(it) },
                wepTxKey = config.wepTxKeyIndex
            )
        }

        config.httpProxy?.let { proxy ->
            network = network.copy(
                proxy = ProxyDto(
                    pac = proxy.pacFileUrl?.toString(),
                    host = proxy.host,
                    port = proxy.port.takeIf { it > 0 },
                    exclusions = proxy.exclusionList?.takeIf { it.isNotEmpty() }?.toList()
                )
            )
        }

        return network
    }

    /**
     * 静态 IP；DHCP 的网络返回 null。
     *
     * `getIpConfiguration` 是隐藏方法，靠 Refine 读；它带的 `IpConfiguration`
     * 只有 `getStaticIpConfiguration` 是公开的。
     */
    private fun staticIpOf(config: WifiConfiguration): StaticIpDto? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null

        val ipConfig = runCatching {
            Refine.unsafeCast<WifiConfigurationHidden>(config).ipConfiguration
        }.getOrNull() ?: return null

        val static = ipConfig.staticIpConfiguration ?: return null
        // 显式声明成可空：Java 侧这两个字段是平台类型，Kotlin 会把防御性 ?. 报成多余
        val linkAddress: LinkAddress? = static.ipAddress
        val dnsServers: List<InetAddress>? = static.dnsServers
        return StaticIpDto(
            address = linkAddress?.toString(),
            gateway = static.gateway?.hostAddress,
            dns = dnsServers?.takeIf { it.isNotEmpty() }?.mapNotNull { it.hostAddress },
            domains = static.domains?.takeIf { it.isNotBlank() }
        )
    }

    /** `allowedKeyManagement` 是个 BitSet，存成「哪些位是 true」的数组。 */
    private fun keyMgmtOf(config: WifiConfiguration): List<Int> {
        // 显式可空：平台类型下 Kotlin 会把 ?: 报成恒等，但系统确实可能返回 null
        val raw: BitSet? = config.allowedKeyManagement
        val bits = raw ?: return emptyList()
        return (0 until bits.size()).filter { bits.get(it) }
    }

    // ==================== 恢复 ====================

    @Suppress("DEPRECATION")
    private fun restoreWifi(context: Context, backup: WifiBackupFile): RestoreResult {
        val manager = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return RestoreResult(0, 0)
        val hidden = Refine.unsafeCast<WifiManagerHidden>(manager)

        // 同名（SSID）的网络已经存在就跳过 —— addNetwork 不做去重，会多出一条重复的
        val existing = hidden.privilegedConfiguredNetworks.orEmpty()
            .mapNotNull { it.SSID }
            .mapTo(HashSet()) { unquote(it) }

        var added = 0
        var skipped = 0
        var failed = 0

        for (network in backup.networks) {
            val ssid = network.ssid
            if (ssid.isBlank()) {
                failed++
                continue
            }
            if (!existing.add(ssid)) {
                skipped++
                continue
            }

            val config = buildWifiConfig(network)
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
        return RestoreResult(added, skipped, failed)
    }

    /** 按导出的字段重建一个 [WifiConfiguration]；重建不了（企业网）返回 null。 */
    @Suppress("DEPRECATION")
    private fun buildWifiConfig(network: WifiNetworkDto): WifiConfiguration? {
        val keyMgmt = network.keyMgmt
        if (keyMgmt.any { it in ENTERPRISE_KEY_MGMT }) return null

        val config = WifiConfiguration()
        config.SSID = quote(network.ssid)
        config.hiddenSSID = network.hidden

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            config.macRandomizationSetting =
                network.macRandom ?: WifiConfiguration.RANDOMIZATION_AUTO
        }

        network.password.takeIf { it.isNotEmpty() }?.let { password ->
            config.preSharedKey = quote(password)
        }

        network.wepKeys?.let { keys ->
            val slots = arrayOfNulls<String>(WIFI_WEP_KEY_COUNT)
            for (index in 0 until minOf(keys.size, WIFI_WEP_KEY_COUNT)) {
                val key = keys[index]
                if (key.isNotEmpty()) slots[index] = quote(key)
            }
            config.wepKeys = slots
            config.wepTxKeyIndex = network.wepTxKey ?: 0
        }

        keyMgmt.forEach { if (it >= 0) config.allowedKeyManagement.set(it) }

        network.proxy?.let { proxy ->
            config.httpProxy = buildProxy(proxy)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            network.ip?.let { ip ->
                // 只有 setter，没有公开 getter，不能写属性语法
                buildIpConfiguration(ip)?.let { config.setIpConfiguration(it) }
            }
        }

        return config
    }

    @Suppress("DEPRECATION")
    private fun buildProxy(proxy: ProxyDto): ProxyInfo? {
        val pac = proxy.pac?.takeIf { it.isNotBlank() }
        if (pac != null) return ProxyInfo.buildPacProxy(Uri.parse(pac))

        val host = proxy.host?.takeIf { it.isNotBlank() } ?: return null
        val port = proxy.port ?: 0
        val exclusions = proxy.exclusions

        return if (exclusions.isNullOrEmpty()) {
            ProxyInfo.buildDirectProxy(host, port)
        } else {
            ProxyInfo.buildDirectProxy(host, port, exclusions)
        }
    }

    /** 按可读字段拼一个 [IpConfiguration]；拼不出来返回 null。 */
    private fun buildIpConfiguration(ip: StaticIpDto): IpConfiguration? =
        // Java 方法不能用具名参数
        StaticIpFactory.build(
            ip.address?.takeIf { it.isNotBlank() },
            ip.gateway?.takeIf { it.isNotBlank() },
            ip.dns,
            ip.domains?.takeIf { it.isNotBlank() }
        )

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
}
