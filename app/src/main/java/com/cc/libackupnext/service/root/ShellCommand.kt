package com.cc.libackupnext.service.root

import com.cc.libackupnext.util.AppLog
import com.topjohnwu.superuser.Shell

/**
 * root 侧的 shell 通道：隐藏 API 拿不到、或者拿得到但分不清成败的动作才走这里。
 *
 * `:root` 进程本身已是 uid 0，libsu 直接用现成的 root shell，不会再弹授权。
 *
 * 分两层：底层 [runShell] 只跑命令、把退出码和输出交出去；上层 [runShellChecked]
 * 按「退出码 + 必须出现的关键字」判成败，失败抛异常。
 */

private const val TAG = "ShellCommand"

/**
 * 一次 shell 调用的结果。
 *
 * @property ok 命令是否以退出码 0 结束。注意 `pm` 系列失败时也可能为 true，
 *   要判业务成败还得看 [output] 里的关键字。
 */
internal class ShellResult(val ok: Boolean, val code: Int, val output: String)

/** 失败时的可读原因：输出为空就报退出码。 */
internal fun ShellResult.reason(): String = output.ifBlank { "退出码 $code" }

/** 跑一条 shell 命令，不判成败，把退出码和输出都交给调用方。 */
internal fun runShell(command: String): ShellResult = try {
    val result = Shell.cmd(command).exec()
    ShellResult(
        ok = result.isSuccess,
        code = result.code,
        output = (result.out + result.err).joinToString("\n").trim(),
    )
} catch (t: Throwable) {
    ShellResult(ok = false, code = -1, output = "执行失败：${t.message ?: t.javaClass.simpleName}")
}

/** pm 的失败格式是 `Failure [REASON: detail]`，括号里才是要展示的部分。 */
private val FAILURE_BLOCK = Regex("""Failure \[([^\]]+)]""")

/** 行首的 `Error:` 只是包装。 */
private val ERROR_PREFIX = Regex("""^Error:\s*""", RegexOption.IGNORE_CASE)

private val COLON_SPACE = Regex(""":\s+""")
private val WHITESPACE = Regex("""\s+""")

/**
 * 把 shell 输出里真正的原因剥出来。
 *
 * pm 的失败长这样：`Failure [INSTALL_FAILED_USER_RESTRICTED: Installation via USB is disabled]`，
 * 前面还可能混着 avc denied 之类的系统日志。UI 只该看到
 * `INSTALL_FAILED_USER_RESTRICTED Installation via USB is disabled`。
 */
private fun cleanReason(raw: String): String {
    // Failure [REASON: detail] -> REASON detail。只有这种块里的第一个冒号是分隔符，
    // 要换成空格；其他情况（异常类名后面那个冒号）必须留着。
    FAILURE_BLOCK.find(raw)?.let { block ->
        return block.groupValues[1]
            .replaceFirst(COLON_SPACE, " ")
            .replace(WHITESPACE, " ")
            .trim()
    }

    return raw.trim()
        .replace(ERROR_PREFIX, "")
        .replace(WHITESPACE, " ")
        .trim()
        .ifBlank { raw.trim() }
}

/**
 * 跑一条必须成功的命令，成功返回输出、失败抛 [IllegalStateException]。
 *
 * 异常 message 只放原因：UI 的 toast 已经拼过「XX失败：」，带上 [failText] 会重复。
 * 而且原因会经 [cleanReason] 剥掉 `Failure [...]` / `Error:` 这类包装，只留
 * `INSTALL_FAILED_USER_RESTRICTED Installation via USB is disabled` 这种正文。
 * [failText] 只进日志，用来排查是哪一步挂的。
 *
 * @param expect 成功时输出里必须出现的关键字。`pm` 系列失败时退出码也可能是 0，
 *   只看退出码会把失败当成功；`am` 系列成功时没有输出，传 null 就只看退出码。
 */
internal fun runShellChecked(command: String, failText: String, expect: String? = null): String {
    val result = runShell(command)
    val reason = when {
        !result.ok -> result.reason()
        expect != null && !result.output.contains(expect, ignoreCase = true) -> result.reason()
        else -> return result.output
    }
    // 日志留原文（含 avc denied 之类），给用户只留原因
    AppLog.w(TAG, "$failText：$reason")
    throw IllegalStateException(cleanReason(reason))
}
