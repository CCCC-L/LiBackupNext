package com.cc.libackupnext.service.gateway

import com.cc.libackupnext.data.FailureReason

/** 后端失败的统一判定：是不是断连，不持有连接、不发请求。 */
object BackendErrors {

    /** 断连兜底文案；正常情况下 message 已经是 [FailureReason.text] 的那一句。 */
    val REMOTE_INTERRUPTED_MESSAGE: String = FailureReason.UNREACHABLE.text

    /**
     * 这次失败是不是「远程不可用」：断连，或远程服务根本没起来。
     *
     * 两者对用户是同一件事（连不上），UI 都要提示、批量都要停批。
     */
    fun isConnectionLost(t: Throwable?): Boolean =
        t is RemoteInterruptedException || t is RemoteServeException

    /** 完整原文，只有认不出原因时才有；UI 据此决定要不要显示「完整错误」。 */
    fun detailOf(t: Throwable?): String? =
        generateSequence(t) { it.cause }
            .filterIsInstance<BackendFailureException>()
            .firstNotNullOfOrNull { it.detail?.takeIf(String::isNotBlank) }
}

/**
 * 后端失败的统一异常：[message] 是界面文案，[detail] 只给未知原因保留原文，
 * 日志靠 cause 链里的 [BackendRawException]。
 */
open class BackendFailureException(
    message: String,
    val detail: String? = null,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

/**
 * 远程仓库连接中断。用类型判定而不是比文案：文案会随原因变（连不上 / 认证 / 超时）。
 */
class RemoteInterruptedException(
    message: String,
    detail: String? = null,
    cause: Throwable? = null
) : BackendFailureException(message, detail, cause)

/**
 * 远程服务（rclone serve restic）起不来。算传输层问题：后面每笔都会失败，必须停批。
 */
class RemoteServeException(
    message: String,
    detail: String? = null,
    cause: Throwable? = null
) : BackendFailureException(message, detail, cause)
