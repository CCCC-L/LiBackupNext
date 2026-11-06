package com.cc.libackupnext.util

import android.content.Context
import android.net.Uri
import com.topjohnwu.superuser.Shell
import java.io.BufferedOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 日志导出：往系统文件选择器（SAF CreateDocument）返回的 Uri 直接流式写 zip。
 *
 * 不需要 FileProvider，也不需要临时文件。日志本来就是实时落盘的，
 * 所以功能异常（没崩溃、结果不对）之后随时导出都是完整的。
 * 调用方负责放到 IO 线程。
 */
object LogReport {

    const val MIME_TYPE = "application/zip"
    private const val LOGCAT_LINES = 500

    fun suggestedFileName(): String =
        "LiBackupNext-logs-" +
                SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".zip"

    fun writeTo(context: Context, uri: Uri): Result<Unit> = runCatching {
        val output = context.contentResolver.openOutputStream(uri, "w")
            ?: error("系统没有返回可写的输出流")

        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            put(zip, "README.txt", readme())
            put(zip, "environment.txt", AppLog.environmentText())
            put(zip, "operations.txt", AppLog.operationsText())

            // 主进程 / root 进程日志、崩溃报告、Rust panic 痕迹，全都在 logs/ 下。
            // operations.log 只是生成 operations.txt 的原始索引，内容已全在后者里，不重复打包。
            AppLog.logFiles()
                .filter { it.name != "pending_crash" && it.name != "operations.log" }
                .forEach { file ->
                    put(
                        zip,
                        file.name,
                        AppLog.sanitize(runCatching { file.readText() }.getOrDefault(""))
                    )
                }

            // 有 root 就能拿到完整 logcat（含 :root 进程与 native 崩溃线索）
            put(zip, "logcat.txt", AppLog.sanitize(readLogcat()))
        }
    }

    private fun put(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun readLogcat(): String = runCatching {
        val result = Shell.cmd("logcat -d -t $LOGCAT_LINES -v threadtime").exec()
        if (result.isSuccess) {
            result.out.joinToString("\n")
        } else {
            "# logcat 读取失败（可能尚未获得 root，或系统限制了读取）\n"
        }
    }.getOrElse { "# logcat 读取异常：${it.message}\n" }

    private fun readme(): String = """
        LiBackupNext 日志包

        environment.txt  软件版本 / 设备 / 系统核心信息（排查时先看这个）
        operations.txt   操作索引：一次业务操作一行，拿 op 值去日志里搜完整链路
        main.log         主进程实时日志
        root.log         :root 服务实时日志
        crash-*.txt      未捕获异常报告（含崩溃前轨迹）
        rust-panic.txt   Rust panic 留痕（panic=abort 之前写入）
        logcat.txt       最近 $LOGCAT_LINES 行系统日志

        日志格式：时间 级别 [进程] op=操作ID 标签 | 内容
        同一 op 值即同一次业务操作跨主进程 / root 服务的完整链路。

        密码类字段在导出前已做脱敏，但 logcat 由系统产生，可能包含应用包名、
        文件路径或其他应用的输出，请在发送前自行检查。
    """.trimIndent()
}
