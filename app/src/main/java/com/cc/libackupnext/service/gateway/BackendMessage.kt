package com.cc.libackupnext.service.gateway

import com.cc.libackupnext.util.AppLog

/**
 * 后端返回的「完整原文留日志 / 精简原文给 UI」收敛。
 *
 * 两个后端都不翻译、不归类，只做格式精简，不改一个字的内容。
 * - rclone：剥掉英文 method 包装，压成单行、限长；
 * - rustic：Rust 侧**只返回事实**（rustic_core 的英文 message），不再拼中文业务标签；
 *   rustic_core 的 Display 会在 message 前加「rustic_core experienced an error
 *   related to …」头部行和「Message:」小节标记，UI 只显示 Message: 之后的正文，
 *   这个英文包装剥掉。Display 尾部的 Backtrace 小节由 Rust 侧 describe_error()
 *   在源头去掉。远程连接失败的 `Cannot connect to remote repository (...)` 保留。
 *
 * 完整响应照旧无条件写日志（含被剥掉的中文标签），截断部分也能从日志 /
 * [BackendRawException] 的 cause 链找回。业务层 Result 成败、远程断连归一化不变。
 *
 * 脱敏按源区分，只记录响应、不记录请求：
 * - rclone：inline 配置带明文密码 / key_pem，ensureServe 的成功响应里还有带
 *   token 的 basic-auth URL，写日志前必须打码；
 * - rustic：Rust 侧 describe_error() 已经 redact_userinfo() 抹掉 URL userinfo，
 *   仓库密码只在 root 侧 setRepo() 注入、从不进响应，所以 Kotlin 层原样留档，
 *   再跑一遍脱敏只会把 `token=xxx` 这类诊断字段误打成 ***。
 *
 * 分层：Rust / DAO 保留原文，本类是主进程 service 侧的展示适配，
 * RcloneClient / RusticRepoGateway 在拿到 AIDL 响应时调一次即可，UI 不改一行。
 */
enum class BackendSource { RCLONE, RUSTIC }

/** [code] 只进日志，不进 UI 文案。 */
data class BriefMessage(val text: String, val code: String? = null)

/** 原文放进 cause 链：即便日志被裁剪，AppLog 写出的 Caused by 里仍有一份。 */
class BackendRawException(source: BackendSource, raw: String) :
    IllegalStateException(BackendMessage.logText(source, raw))

object BackendMessage {

    /**
     * UI 文案上限。保头截断：远程失败的英文结论在最前面，底层 message 的关键
     * 信息也常在前半段；取尾段会把结论切掉。
     */
    private const val MAX_BRIEF = 180

    private val HTTP_CODE = Regex("\\b([1-5]\\d{2})\\b")

    /** 冒号前含中文、且不含 URL / 引号特征的 Rust 侧业务标签。 */
    private val RUSTIC_LABEL = Regex(
        "^(?=[^：:\\n]*[一-龥])[^：:\\n\"'/]{1,60}[：:]\\s*"
    )

    /** init_repo 的复合包装：只取「打开失败」括号里的第一段 message。 */
    private val RUSTIC_COMPOUND = Regex(
        "打开失败（(.*?)）；尝试初始化也失败（(.*?)）", RegexOption.DOT_MATCHES_ALL
    )

    /**
     * rustic_core 的 Display 固定把错误写成：
     * 「rustic_core experienced an error related to <kind>.」+ 空行 +「Message:」
     * + 真正的 message。前两段只是框架文案，UI 只留 Message: 之后的内容。
     */
    private val RUSTIC_DISPLAY_HEADER = Regex(
        "`?rustic_core`? experienced an error related to `[^`]*`\\.\\s*Message\\s*[:：]\\s*"
    )

    private val URL_AUTH = Regex("(?i)(https?://)[^/\\s:@]+:[^/\\s@]+@")
    private val JSON_SECRET = Regex(
        "(?i)(\"(?:pass|password|passwd|pwd|token|secret|key_pem|private_key|apiKey)\"\\s*:\\s*)\"[^\"]*\""
    )
    private val BASIC_AUTH = Regex("(?i)(basic\\s+)[A-Za-z0-9+/=]+")
    private val KV_SECRET = Regex("(?i)((?:password|passwd|pwd|token|secret|pass)=)[^&\\s,\"]+")

    /**
     * 只给 rclone 用：AppLog.sanitize 只在导出时跑，且覆盖不到 `http://user:pass@`
     * 这种内联凭据，所以写日志前必须自己做一层。
     */
    fun redact(raw: String): String {
        var text = URL_AUTH.replace(raw, "$1***@")
        text = JSON_SECRET.replace(text, "$1\"***\"")
        text = BASIC_AUTH.replace(text, "$1***")
        return KV_SECRET.replace(text, "$1***")
    }

    /**
     * 写日志 / 进 cause 链前的原文处理。rustic 的 error 在 Rust 侧已脱敏，
     * 原样返回；这个函数不参与 [shorten]，不影响 UI 看到的文本。
     */
    fun logText(source: BackendSource, raw: String): String =
        if (source == BackendSource.RCLONE) redact(raw) else raw

    /**
     * 纯函数：剥掉后端加的包装 → 压成单行 → 保头限长。
     * 不翻译、不归类、不改写措辞；只有真的截断时才补一个省略号，
     * 完整原文照旧写进日志，UI 不再提示去查日志。
     */
    fun shorten(source: BackendSource, raw: String): BriefMessage {
        val wrapped = stripWrapper(raw)
        val clean = if (source == BackendSource.RUSTIC) unwrapRusticMessage(wrapped) else wrapped
        return BriefMessage(compact(clean), extractCode(clean))
    }

    /**
     * 完整响应进日志 + 返回 UI 文案。失败无条件写 ERROR；成功只写 DEBUG
     */
    fun record(
        source: BackendSource,
        raw: String,
        error: String?,
        ok: Boolean,
        label: String
    ): BriefMessage {
        val brief = if (ok) {
            BriefMessage("")
        } else {
            shorten(source, error?.takeIf { it.isNotBlank() } ?: raw)
        }
        val codePart = brief.code?.let { " code=$it" }.orEmpty()
        // 短文本未经处理时 brief 与原文相同，不必在日志里再写一遍
        val briefPart = if (ok || brief.text == raw.trim()) "" else " brief=${brief.text}"
        AppLog.logLong(
            level = if (ok) AppLog.Level.DEBUG else AppLog.Level.ERROR,
            tag = if (source == BackendSource.RCLONE) "RcloneResponse" else "RusticResponse",
            message = "$label ok=$ok$codePart$briefPart 完整响应=${logText(source, raw)}"
        )
        return brief
    }

    /** 只剥后端加的英文包装，不动错误内容本身；中文业务标签单独处理。 */
    private fun stripWrapper(raw: String): String = raw.trim()
        .replace(Regex("(?i)error in JsonRPC\\s*[：:]?\\s*"), "")
        .replace(Regex("(?i)^(?:error|failed|failure)\\s*[：:]\\s*"), "")

    /**
     * rustic：只去掉 rustic_core 的 Display 头部（英文框架文案），保留 `Message:` 之后的正文。
     *
     * Rust 侧已经不再拼中文业务标签 —— 错误前缀由调用方（usecase / UI）按场景决定，
     * 底层只回事实。
     */
    private fun unwrapRusticMessage(wrapped: String): String {
        val message = RUSTIC_DISPLAY_HEADER.replace(wrapped, "")
        return message.takeIf { it.isNotBlank() } ?: wrapped
    }

    /** 压平空白成单行，超长保头截断只补省略号；短文本原样返回，不加任何后缀。 */
    private fun compact(clean: String): String {
        val collapsed = clean.replace(Regex("\\s+"), " ").trim()
        if (collapsed.isBlank()) return "操作失败"
        if (collapsed.length <= MAX_BRIEF) return collapsed
        return collapsed.take(MAX_BRIEF) + "…"
    }

    private fun extractCode(clean: String): String? =
        HTTP_CODE.find(clean)?.groupValues?.get(1)?.let { "HTTP $it" }
}
