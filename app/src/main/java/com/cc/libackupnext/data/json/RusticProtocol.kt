@file:OptIn(ExperimentalSerializationApi::class)

package com.cc.libackupnext.data.json

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * rustic 通道的所有 JSON 结构。
 *
 * 字段名与 `rustic_bridge/src/lib.rs` 里的 serde 结构体一一对应，一个字都不能改：
 * 请求由 Rust 的 `Deserialize` 解析，响应由 Rust 的 `Serialize` 生成。
 * 没有 `#[serde(default)]` 的 Rust 字段在这里不设 Kotlin 默认值，保证一定会被写出。
 */

/** 任意请求里取出透传的操作 id（主进程 / root 两侧日志串成一条链）。 */
@Serializable
data class OperationEnvelope(val op: String = "")

/**
 * Rust 侧 BridgeResponse：`{"ok":true}` 或 `{"ok":false,"error":"...","reason":"password"}`。
 *
 * [reason] 是 [com.cc.libackupnext.data.FailureReason] 的 wire 值；缺省表示底层
 * 也给不出结论，主进程只能拿原文兜底。
 */
@Serializable
data class OkResponse(
    // 失败响应由主进程重新拼成 JSON 传给上层，默认值不写出就会丢字段
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val ok: Boolean = false,
    val error: String? = null,
    val reason: String? = null
)

// ==================== 主进程 -> root / Rust 的请求 ====================

@Serializable
data class ListSnapshotsRequest(@SerialName("package_name") val packageName: String = "")

@Serializable
data class DeleteSnapshotsRequest(val ids: List<String>)

@Serializable
data class UpdateDescriptionRequest(
    val ids: List<String>,
    val description: String? = null
)

@Serializable
data class ExportSnapshotRequest(val id: String, val dest: String)

/** 初始化 / 校验仓库的候选凭据，只随这一次请求下传，不写全局配置。 */
@Serializable
data class InitRepoRequest(
    val path: String,
    val password: String,
    val options: Map<String, String> = emptyMap(),
    /** true = 直接建库（主进程已判定是空仓库）；false = 只打开。 */
    @SerialName("force_init") val forceInit: Boolean = false
)

/** 查仓库结构：只列目录，不需要密码。 */
@Serializable
data class RepoLayoutRequest(
    val path: String,
    val options: Map<String, String> = emptyMap()
)

/** 五类文件的存在情况；「算不算完整」由 `service.gateway.RepoLayout` 判定。 */
@Serializable
data class RepoLayoutResponse(
    val ok: Boolean = false,
    val config: Boolean = false,
    val keys: Boolean = false,
    val data: Boolean = false,
    val index: Boolean = false,
    val snapshots: Boolean = false,
    val error: String? = null,
    val reason: String? = null
)

/**
 * 主进程发给 root 的备份请求。
 *
 * `apk / obb / user / data / permissions` 这些 key 就是 [com.cc.libackupnext.data.SnapshotKind.key]，
 * 由 root 侧 RusticRequestBuilder 逐个 optBoolean 读取；固定 5 项，不用动态 Map。
 */
@Serializable
data class BackupOptions(
    val op: String = "",
    val packageName: String = "",
    val appName: String = "",
    val version: String = "",
    val userId: Int = 0,
    val apk: Boolean = false,
    val obb: Boolean = false,
    val user: Boolean = false,
    val data: Boolean = false,
    val permissions: Boolean = false,
    val timestamp: Long = 0,
    val includeIcon: Boolean = true,
    val parents: Map<String, String>? = null,
    val batch: String? = null,
    /**
     * 固定目标（Wi-Fi / 联系人 / 通话记录 / 短信）要备份哪几项，值是 [SnapshotKind.key]。
     *
     * 应用 / 文件夹不看这个字段（它们由上面几个 bool 决定）；
     * null 表示「全备」，老客户端不带这个字段时行为不变。
     */
    val kinds: List<String>? = null,
    val overrides: Map<String, SnapshotOverrideDto>? = null
)

@Serializable
data class SnapshotOverrideDto(
    val label: String = "",
    val tags: String = "",
    val description: String? = null
)

/** 主进程发给 root 的恢复请求（root 侧自己消费，不直接透传给 Rust）。 */
@Serializable
data class RestoreSnapshotsRequest(
    val op: String = "",
    val userId: Int = 0,
    val packageName: String = "",
    val delete: Boolean = true,
    val items: List<RestoreKindItem> = emptyList()
)

@Serializable
data class RestoreKindItem(val kind: String, val id: String)

// ==================== root -> Rust 的请求 ====================

/** Rust 的 BackupRequest / BackupItem。 */
@Serializable
data class RusticBackupRequest(
    val items: List<BackupItemDto>,
    val excludes: List<String> = emptyList(),
    val batch: String? = null
)

@Serializable
data class BackupItemDto(
    val stage: String,
    val label: String,
    val tags: List<String>,
    val sources: List<String>,
    @SerialName("as_path") val asPath: String? = null,
    @SerialName("skip_reason") val skipReason: String? = null,
    val description: String? = null,
    val parent: String? = null,
    val excludes: List<String> = emptyList(),
    /**
     * 判断文件有没有变时不比较 inode。
     *
     * 只对 inode 不可信的源打开（SD 卡 / 非主用户这类 FUSE 视图），
     * 规则见 [com.cc.libackupnext.service.root.ignoreInodeFor]。
     */
    @SerialName("ignore_inode") val ignoreInode: Boolean = false,
    /** Rust 的 BackupItem 没有这个字段（serde 忽略未知键），只给 Kotlin 侧标记用。 */
    val system: Boolean? = null
)

/** Rust 的 RestoreRequest / RestoreItem（单快照解包）。 */
@Serializable
data class RestoreTreeRequest(val items: List<RestoreTreeItem>)

@Serializable
data class RestoreTreeItem(
    val id: String,
    val dest: String,
    val delete: Boolean = false,
    /** 快照类型（apk / user / data …）。Rust 侧用它给字节进度帧标 stage。 */
    val stage: String = ""
)

// ==================== Rust -> 主进程的响应 ====================

/** Rust 的 SnapshotInfo。 */
@Serializable
data class SnapshotDto(
    val id: String = "",
    val label: String = "",
    val tags: String = "",
    val time: Long = 0,
    val size: Long = 0,
    val description: String? = null
)

@Serializable
data class ListSnapshotsResponse(
    val ok: Boolean = false,
    val snapshots: List<SnapshotDto> = emptyList(),
    val error: String? = null,
    val reason: String? = null
)

/** Rust 的 OverviewSnapshot。 */
@Serializable
data class OverviewSnapshotDto(
    val id: String = "",
    val label: String = "",
    val size: Long = 0
)

/** Rust 的 PackageOverview。 */
@Serializable
data class PackageOverviewDto(
    @SerialName("package") val packageName: String = "",
    val count: Int = 0,
    val time: Long = 0,
    val tags: String = "",
    val description: String? = null,
    val snapshots: List<OverviewSnapshotDto> = emptyList(),
    @SerialName("has_apk") val hasApk: Boolean = false,
    @SerialName("apk_tags") val apkTags: String? = null,
    @SerialName("apk_time") val apkTime: Long = 0,
    @SerialName("has_icon") val hasIcon: Boolean = false
)

@Serializable
data class OverviewResponse(
    val ok: Boolean = false,
    val packages: List<PackageOverviewDto> = emptyList(),
    val error: String? = null,
    val reason: String? = null
)

@Serializable
data class CleanupResponse(
    val ok: Boolean = false,
    val removed: Int = 0,
    val error: String? = null,
    val reason: String? = null
)

@Serializable
data class EstimateResponse(
    val ok: Boolean = false,
    val bytes: Long = 0,
    val error: String? = null,
    val reason: String? = null
)

/** root 侧统计出来的三类系统数据条数；key 是 SnapshotKind.key。 */
@Serializable
data class SystemDataCountsResponse(
    val ok: Boolean = false,
    val counts: Map<String, Int> = emptyMap(),
    val error: String? = null
)

/**
 * 批量判断目录是否存在。路径必须是**物理路径**（`/data/media/<userId>/…`）：
 * root 在 `/storage/emulated/<userId>` 这种 FUSE 视图上会被权限挡住。
 */
@Serializable
data class StatDirsRequest(val paths: List<String> = emptyList())

@Serializable
data class StatDirsResponse(
    val ok: Boolean = false,
    val existing: List<String> = emptyList(),
    val error: String? = null
)
