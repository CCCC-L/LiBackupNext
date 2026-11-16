package com.cc.libackupnext.data.json

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * rclone 通道的请求 / 响应结构。
 *
 * 请求字段名对齐 `rclone_bridge/src/lib.rs` 的 `ListDirsRequest` / `EnsureServeRequest`；
 * `fixup` 只有 Kotlin 侧读（Rust 的 serde 会忽略未知键），所以也放在这里。
 * inline 配置由 service/RemoteBackend 按协议现拼，不在这里建 DTO ——
 * 各协议的可选键不同，固定字段的类型化 DTO 反而挡住了新协议。
 */

@Serializable
data class ListDirsRequest(
    val lib: String,
    val fs: String,
    val path: String = "",
    val create: String? = null,
    val fixup: String? = null
)

@Serializable
data class ListDirsResponse(
    val ok: Boolean = false,
    val dirs: List<String> = emptyList(),
    val error: String? = null
)

@Serializable
data class EnsureServeRequest(val lib: String, val fs: String, val key: String)

@Serializable
data class EnsureServeResponse(
    val ok: Boolean = false,
    val url: String? = null,
    val error: String? = null
)

/** rustic_backend 的 options；rest_url 只在本次会话有效，绝不落盘。 */
@Serializable
data class BackendOptions(@SerialName("rest_url") val restUrl: String)
