package com.cc.libackupnext.ui.util

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

/** `1.2 GB` / `356.0 MB` / `12.0 KB` / `512 B`。 */
fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f GB", bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024)
    bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
