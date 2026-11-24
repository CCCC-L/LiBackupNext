package com.cc.libackupnext.service.gateway

import com.cc.libackupnext.data.FailureReason
import com.cc.libackupnext.util.AppLog

/**
 * 后端失败的展示适配：已知原因给一句中文，未知原因拿原文兜底。
 *
 * 认得原因时原文只进日志；认不出才压成单行显示，完整原文放 `detail`。
 * 脱敏只给 rclone 做（inline 配置和 basic-auth URL 带明文凭据），
 * rustic 侧已在 Rust 里脱敏。
 */
enum class BackendSource { RCLONE, RUSTIC }

/**
 * @param text 给用户看的文案：已知原因是一句中文，未知原因是压平的原文
 * @param detail 完整原文，**只有未知原因才给**
 */
data class BriefMessage(
    val text: String,
    val detail: String? = null
)

/** 原文放进 cause 链，日志被裁剪时 Caused by 里还有一份。 */
class BackendRawException(source: BackendSource, raw: String) :
    IllegalStateException(BackendMessage.logText(source, raw))

object BackendMessage {

    /**
     * 单行文案上限。保头截断：底层 message 的关键信息常在前半段，
     * 取尾段会把最有用的部分切掉。
     */
    private const val MAX_BRIEF = 180

    /** 完整原文上限：够放下 rustic 的 Message + Context + Caused by 三节。 */
    private const val MAX_DETAIL = 2000

    /** rustic_core 的 Display 头部：`experienced an error related to X.` + `Message:`。 */
    private val RUSTIC_DISPLAY_HEADER = Regex(
        "`?rustic_core`? experienced an error related to `[^`]*`\\.\\s*Message\\s*[:：]\\s*"
    )

    private val URL_AUTH = Regex("(?i)(https?://)[^/\\s:@]+:[^/\\s@]+@")
    private val JSON_SECRET = Regex(
        "(?i)(\"(?:pass|password|passwd|pwd|token|secret|key_pem|private_key|apiKey)\"\\s*:\\s*)\"[^\"]*\""
    )
    private val BASIC_AUTH = Regex("(?i)(basic\\s+)[A-Za-z0-9+/=]+")
    private val KV_SECRET = Regex("(?i)((?:password|passwd|pwd|token|secret|pass)=)[^&\\s,\"]+")

    /** 只给 rclone：AppLog 的脱敏覆盖不到 `http://user:pass@` 这种内联凭据。 */
    private fun redact(raw: String): String {
        var text = URL_AUTH.replace(raw, "$1***@")
        text = JSON_SECRET.replace(text, "$1\"***\"")
        text = BASIC_AUTH.replace(text, "$1***")
        return KV_SECRET.replace(text, "$1***")
    }

    /** 写日志 / 进 cause 链前的原文处理；不影响 UI 看到的文本。 */
    fun logText(source: BackendSource, raw: String): String =
        if (source == BackendSource.RCLONE) redact(raw) else raw

    /** 原因 → 文案；认不出原因（null）才拿原文兜底。 */
    fun shorten(source: BackendSource, raw: String, reason: FailureReason?): BriefMessage {
        // 已知原因：只给一句中文，原文不进 UI（只进日志）
        reason?.let { return BriefMessage(it.text) }

        val wrapped = stripWrapper(raw)
        val clean = if (source == BackendSource.RUSTIC) unwrapRusticMessage(wrapped) else wrapped
        return BriefMessage(compact(clean), detailOf(clean))
    }

    /** 完整响应进日志 + 返回 UI 文案；失败写 ERROR，成功只写 DEBUG。 */
    fun record(
        source: BackendSource,
        raw: String,
        error: String?,
        ok: Boolean,
        label: String,
        reason: FailureReason? = null
    ): BriefMessage {
        val brief = if (ok) {
            BriefMessage("")
        } else {
            shorten(source, error?.takeIf { it.isNotBlank() } ?: raw, reason)
        }
        val reasonPart = reason?.let { " reason=${it.wire}" }.orEmpty()
        // 短文本未经处理时 brief 与原文相同，不必在日志里再写一遍
        val briefPart = if (ok || brief.text == raw.trim()) "" else " brief=${brief.text}"
        AppLog.logLong(
            level = if (ok) AppLog.Level.DEBUG else AppLog.Level.ERROR,
            tag = if (source == BackendSource.RCLONE) "RcloneResponse" else "RusticResponse",
            message = "$label ok=$ok$reasonPart$briefPart 完整响应=${logText(source, raw)}"
        )
        return brief
    }

    /** 只剥后端加的英文包装，不动错误内容本身；中文业务标签单独处理。 */
    private fun stripWrapper(raw: String): String = raw.trim()
        .replace(Regex("(?i)error in JsonRPC\\s*[：:]?\\s*"), "")
        .replace(Regex("(?i)^(?:error|failed|failure)\\s*[：:]\\s*"), "")

    /** 去掉 rustic_core 的 Display 头部，保留 `Message:` 之后的正文。 */
    private fun unwrapRusticMessage(wrapped: String): String {
        val message = RUSTIC_DISPLAY_HEADER.replace(wrapped, "")
        return message.takeIf { it.isNotBlank() } ?: wrapped
    }

    /** 压平空白成单行，超长保头截断。 */
    private fun compact(clean: String): String {
        val collapsed = clean.replace(Regex("\\s+"), " ").trim()
        if (collapsed.isBlank()) return "操作失败"
        if (collapsed.length <= MAX_BRIEF) return collapsed
        return collapsed.take(MAX_BRIEF) + "…"
    }

    /** 完整原文：保留换行（`Caused by:` 是诊断线索），只做长度兜底。 */
    private fun detailOf(clean: String): String {
        val trimmed = clean.trim()
        if (trimmed.isBlank()) return "操作失败"
        if (trimmed.length <= MAX_DETAIL) return trimmed
        return trimmed.take(MAX_DETAIL) + "…"
    }
}
