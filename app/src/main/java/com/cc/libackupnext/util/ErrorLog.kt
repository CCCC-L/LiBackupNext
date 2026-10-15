package com.cc.libackupnext.util

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 进程内的错误日志，只存在内存里，应用重启就清空。
 *
 * 收集两类问题：
 * 1. 业务里被 catch 住、以前只弹 Toast / 写 logcat 的异常；
 * 2. 没人捕获的崩溃（后台线程记录后继续跑，主线程仍然交给系统）。
 */
object ErrorLog {

    /** 最多保留多少条，避免无限增长。 */
    private const val MAX_ENTRIES = 200

    data class Entry(
        val time: Long,
        val tag: String,
        val message: String,
        val detail: String?
    ) {
        val timeText: String
            get() = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(time))
    }

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    /** 写 logcat 的同时记进错误日志页。 */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        add(tag, message, throwable)
    }

    fun add(tag: String, message: String, throwable: Throwable? = null) {
        // 协程被取消（比如切换用户、重新加载）是正常流程，不是错误
        if (throwable is CancellationException) return

        val entry = Entry(
            time = System.currentTimeMillis(),
            tag = tag,
            message = message.ifBlank {
                throwable?.javaClass?.simpleName ?: "未知错误"
            },
            detail = throwable?.stackTraceToString()
        )
        _entries.update { current -> (listOf(entry) + current).take(MAX_ENTRIES) }
    }

    fun clear() {
        _entries.value = emptyList()
    }

    /**
     * 安装全局未捕获异常处理。
     *
     * 后台线程崩了只记录（线程结束、应用继续跑）；主线程崩了仍然交给系统，
     * 因为吞掉主线程异常只会得到一个卡死的界面。
     */
    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            add(
                tag = "Uncaught",
                message = "${thread.name}: ${error.message ?: error.javaClass.name}",
                throwable = error
            )

            if (thread.name == "main") {
                previous?.uncaughtException(thread, error)
            } else {
                Log.e("Uncaught", "后台线程 ${thread.name} 崩溃，已记录", error)
            }
        }
    }
}
