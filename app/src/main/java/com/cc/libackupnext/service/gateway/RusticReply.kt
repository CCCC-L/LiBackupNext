package com.cc.libackupnext.service.gateway

import com.cc.libackupnext.data.FailureReason
import com.cc.libackupnext.data.json.OkResponse
import com.cc.libackupnext.service.json.parseJson

/** 一次 rustic 调用失败：原始文本 + 原因（原因由 Rust 判定）。 */
internal data class RusticFailure(
    val message: String,
    val reason: FailureReason? = null
) {
    /** 只有断连类原因才算断连，其余跟网络无关。 */
    val transport: Boolean get() = reason?.isTransport == true
}

/**
 * 解析 root 侧 rustic 调用的返回值：**空串 = 成功**，非空 = 失败响应 JSON
 * （`{"ok":false,"error":"...","reason":"password"}`）。
 *
 * 少数 root 侧分支直接返回一句中文，解析不成 JSON 就按未知原因原样显示。
 */
internal fun parseRusticReply(raw: String?): RusticFailure? {
    if (raw.isNullOrBlank()) return null

    val response = runCatching { raw.parseJson<OkResponse>() }.getOrNull()
        ?: return RusticFailure(raw)
    if (response.ok) return null

    return failureOf(response.error, response.reason, "操作失败")
}

/** 查询类响应（`ListSnapshotsResponse` 等）自带 ok / error / reason，按同一套语义翻成失败。 */
internal fun failureOf(error: String?, reason: String?, fallback: String): RusticFailure =
    RusticFailure(
        message = error?.takeIf { it.isNotBlank() } ?: fallback,
        reason = FailureReason.of(reason)
    )
