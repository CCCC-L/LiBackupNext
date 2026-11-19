package com.cc.libackupnext.service.root

import android.os.SharedMemory
import com.cc.libackupnext.data.json.OkResponse
import com.cc.libackupnext.data.json.OperationEnvelope
import com.cc.libackupnext.service.json.parseJson
import com.cc.libackupnext.service.json.toJsonText
import com.cc.libackupnext.util.AppLog
import java.nio.ByteBuffer

/**
 * 一次 native 调用的通用外壳：映射共享内存 → 写请求帧 → 调 native → 读响应帧。
 */

private const val TAG = "RusticServer"

/** 从请求 JSON 里取主进程透传的操作 id，让两侧日志能串成一条链。 */
internal fun opOf(request: String): String? = runCatching {
    request.parseJson<OperationEnvelope>().op.takeIf { it.isNotBlank() }
}.getOrNull()

/**
 * 简单请求：把 JSON 写进共享内存，调 native，返回空串或错误文案。
 *
 * @param nativeError native 库没加载成功时的固定错误；非空就直接返回它
 * @param failText 失败时的文案前缀，例如「修改备注失败」
 */
internal fun runSimpleNative(
    request: String,
    native: (ByteBuffer) -> Int,
    nativeError: String?,
    failText: String = "操作失败",
): String {
    nativeError?.let { return it }
    val op = opOf(request)
    val startedAt = System.currentTimeMillis()

    return try {
        val shared = SharedMemory.create(RUSTIC_SHARED_MEMORY_NAME, RUSTIC_BUFFER_SIZE)
        try {
            val buffer = shared.mapReadWrite()
            writeFrameAt(buffer, 0, request)

            val code = native(buffer)
            val raw = runCatching { readFrameAt(buffer, 0) }.getOrNull()
            val response = raw?.let {
                runCatching { it.parseJson<OkResponse>() }.getOrNull()
            }
            AppLog.i(
                TAG,
                "$failText native 返回 code=$code 耗时=${System.currentTimeMillis() - startedAt}ms",
                op = op
            )

            // 和原来的 optBoolean("ok", true) 一致：拿不到 / 解析不了响应当成功
            if (code == 0 && (response == null || response.ok)) {
                ""
            } else {
                response?.error.orEmpty().ifBlank { "$failText（code=$code）" }
            }
        } finally {
            shared.close()
        }
    } catch (t: Throwable) {
        AppLog.e(TAG, "${failText}异常", t, op)
        "${failText}异常：${t.message ?: t.javaClass.simpleName}"
    }
}

/**
 * 查询请求：把 JSON 写进共享内存，调 native，把结果 JSON 原样返回给主进程解析。
 *
 * @param nativeError native 库没加载成功时的固定错误；非空就包成 ok=false 的响应
 * @param failText 失败时的文案前缀，例如「查询失败」
 */
internal fun runQueryNative(
    request: String,
    native: (ByteBuffer) -> Int,
    nativeError: String?,
    failText: String = "查询失败",
): String {
    nativeError?.let { error ->
        return OkResponse(ok = false, error = error).toJsonText()
    }
    val op = opOf(request)
    val startedAt = System.currentTimeMillis()

    return try {
        val shared = SharedMemory.create(RUSTIC_SHARED_MEMORY_NAME, RUSTIC_BUFFER_SIZE)
        try {
            val buffer = shared.mapReadWrite()
            writeFrameAt(buffer, 0, request)

            val code = native(buffer)
            val response = runCatching { readFrameAt(buffer, 0) }.getOrNull()
            AppLog.i(
                TAG,
                "$failText native 返回 code=$code 耗时=${System.currentTimeMillis() - startedAt}ms",
                op = op
            )

            response ?: OkResponse(ok = false, error = "$failText（code=$code）").toJsonText()
        } finally {
            shared.close()
        }
    } catch (t: Throwable) {
        AppLog.e(TAG, "${failText}异常", t, op)
        OkResponse(
            ok = false,
            error = "${failText}异常：${t.message ?: t.javaClass.simpleName}"
        ).toJsonText()
    }
}
