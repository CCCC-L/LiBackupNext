package com.cc.libackupnext.ui.util

import com.cc.libackupnext.entity.AppInfo
import com.cc.libackupnext.ui.SortType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// SimpleDateFormat 不是线程安全的，用 ThreadLocal 复用，避免列表每行都 new 一个
private val fullFormat = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
}

private val shortFormat = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue() = SimpleDateFormat("yy-MM-dd HH:mm:ss", Locale.getDefault())
}

/** 完整时间：`2026-10-21 17:03:15`；时间戳无效时返回「未知」。 */
fun formatDateTime(timestamp: Long): String {
    if (timestamp <= 0) return "未知"
    return fullFormat.get()!!.format(Date(timestamp))
}

/** 列表里的紧凑时间：`26-10-21 17:03:15`；时间戳无效时返回「未知」。 */
fun formatShortDateTime(timestamp: Long): String {
    if (timestamp <= 0) return "未知"
    return shortFormat.get()!!.format(Date(timestamp))
}

/**
 * 列表 / 批量页里跟着排序方式变化的时间小字：
 * 按安装时间排显示「安装于 …」、更新时间排显示「更新于 …」、备份时间排显示「备份于 …」。
 *
 * 按名称排序、时间戳无效、或没备份时不显示这一行（返回 null）。
 * [backupTime] 由调用方给：主列表页取备份概况、批量页取当前行的概况。
 */
fun sortTimeLabel(sortType: SortType, app: AppInfo, backupTime: Long?): String? = when (sortType) {
    SortType.INSTALL_TIME -> app.installTime
        .takeIf { it > 0 }
        ?.let { "安装于 ${formatShortDateTime(it)}" }

    SortType.UPDATE_TIME -> app.updateTime
        .takeIf { it > 0 }
        ?.let { "更新于 ${formatShortDateTime(it)}" }

    SortType.BACKUP_TIME -> backupTime
        ?.takeIf { it > 0 }
        ?.let { "备份于 ${formatShortDateTime(it)}" }

    SortType.NAME -> null
}

/** `1.2 GB` / `356.0 MB` / `12.0 KB` / `512 B`。 */
fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f GB", bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024)
    bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
