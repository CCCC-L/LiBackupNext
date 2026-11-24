package com.cc.libackupnext.util

/**
 * 日志保留的纯计算部分，不碰文件系统。
 * 改名顺序写反会互相覆盖，所以抽出来单测；文件操作仍由 [AppLog] 做。
 */
internal object LogRetention {

    /**
     * 改名步骤，必须按序执行：从最老的往后挪，当前文件最后动。
     * @param maxBackups 保留几份历史（不含当前文件），0 表示不留。
     */
    fun rotationPlan(logName: String, maxBackups: Int): List<Pair<String, String>> {
        if (maxBackups < 1) return emptyList()

        val plan = ArrayList<Pair<String, String>>(maxBackups)
        for (i in maxBackups - 1 downTo 1) {
            plan += "$logName.$i" to "$logName.${i + 1}"
        }
        plan += logName to "$logName.1"
        return plan
    }

    /** 过期的，以及按时间倒序超出 [maxFiles] 的，返回文件名由调用方删。 */
    fun crashesToDelete(
        crashes: List<CrashFile>,
        maxFiles: Int,
        now: Long,
        retainMillis: Long
    ): List<String> {
        val cutoff = now - retainMillis

        val expired = crashes.filter { it.modifiedAt < cutoff }.map { it.name }
        val overflow = crashes.filter { it.modifiedAt >= cutoff }
            .sortedByDescending { it.modifiedAt }
            .drop(maxFiles.coerceAtLeast(0))
            .map { it.name }

        return expired + overflow
    }

    /** 只要名字和时间：不引入 [java.io.File]，单测里不用造真实文件。 */
    data class CrashFile(val name: String, val modifiedAt: Long)
}
