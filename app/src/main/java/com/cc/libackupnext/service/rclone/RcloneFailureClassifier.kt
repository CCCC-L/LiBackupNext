package com.cc.libackupnext.service.rclone

import com.cc.libackupnext.data.FailureReason

/**
 * 把 rclone 的英文报错归类。rclone 没有 error_code，只能按特征词判断；
 * 认不出来返回 null，调用方拿原文兜底。
 */
internal object RcloneFailureClassifier {

    /** 整词匹配，否则端口号里的 40123 会被当成认证失败。 */
    private val HTTP_AUTH = Regex("\\b(401|403)\\b")

    private val AUTH_HINTS = listOf("unauthorized", "forbidden", "invalid credentials", "invalid password")

    /** 不把 `dial tcp` 当判据：它只是前缀，后面可能是 refused 也可能是 timeout。 */
    private val UNREACHABLE_HINTS = listOf(
        "couldn't connect", "connection refused",
        "no such host", "no route to host", "connection reset"
    )
    /** 含中文「超时」：rclone_bridge 自己的 serve/start 超时也走这条。 */
    private val TIMEOUT_HINTS = listOf("timeout", "deadline exceeded", "超时")

    fun classify(raw: String): FailureReason? {
        val text = raw.lowercase()

        // 认证优先：「couldn't connect ... 401」同时命中两类，但用户该改的是账号密码
        if (HTTP_AUTH.containsMatchIn(text) || AUTH_HINTS.any { it in text }) {
            return FailureReason.AUTH
        }
        // 超时比「连不上」更具体：i/o timeout 也常和 dial tcp 一起出现
        if (TIMEOUT_HINTS.any { it in text }) return FailureReason.TIMEOUT
        if (UNREACHABLE_HINTS.any { it in text }) return FailureReason.UNREACHABLE
        return null
    }
}
