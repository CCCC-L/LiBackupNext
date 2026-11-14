package com.cc.libackupnext.data.json

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * rclone 通道的 JSON 结构，以及 rclone / rustic 后端的 inline 配置。
 *
 * 请求字段名对齐 `rclone_bridge/src/lib.rs` 的 `ListDirsRequest` / `EnsureServeRequest`；
 * `fixup` 只有 Kotlin 侧读（Rust 的 serde 会忽略未知键），所以也放在这里。
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

/** 本地目录浏览的 inline 配置：rclone 的 local 后端，从文件系统根开始。 */
@Serializable
data class LocalFsConfig(
    val type: String,
    @SerialName("_root") val root: String
)

/**
 * 远程服务的 inline 配置。字段按协议分支填写，没用到的键一律不写 ——
 * rclone 对未知 option 会直接报错，所以这里所有可选字段都是 null。
 */
@Serializable
data class RcloneFsConfig(
    val type: String,
    @SerialName("_root") val root: String,
    val url: String? = null,
    val user: String? = null,
    val pass: String? = null,
    val host: String? = null,
    val port: String? = null,
    @SerialName("key_pem") val keyPem: String? = null,
    /** 本项目自定义键：Rust 侧会剥离它，转成 RC 顶层的 no_check_certificate。 */
    val insecure: Boolean? = null
)

/** rustic_backend 的 options；rest_url 只在本次会话有效，绝不落盘。 */
@Serializable
data class BackendOptions(@SerialName("rest_url") val restUrl: String)
