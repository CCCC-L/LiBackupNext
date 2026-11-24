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
            put(zip, "environment.txt", AppLog.environmentText())
            put(zip, "operations.txt", AppLog.operationsText())

            // logs/ 下全量打包；operations.log* 内容已在 operations.txt，不重复。
            AppLog.logFiles()
                .filter {
                    it.name != "pending_crash" && !it.name.startsWith("operations.log")
                }
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
}
