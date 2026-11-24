package com.cc.libackupnext.service.root

import android.os.SharedMemory
import com.cc.libackupnext.data.json.OkResponse
import com.cc.libackupnext.data.json.OperationEnvelope
import com.cc.libackupnext.service.IBackupProgressCallback
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
 * 失败响应编成 JSON 回给主进程：空串只表示成功，失败必须带上 `reason`，
 * 否则主进程只能靠一段英文猜原因。
 */
internal fun failureJson(response: OkResponse?, fallback: String): String = OkResponse(
    ok = false,
    error = response?.error.orEmpty().ifBlank { fallback },
    reason = response?.reason
).toJsonText()

/**
 * 简单请求：把 JSON 写进共享内存，调 native，成功返回空串、失败返回响应 JSON。
 *
 * @param nativeError native 库没加载成功时的固定错误；非空就直接返回它
 * @param failText 失败时的文案前缀，例如「修改备注失败」
 * @param onProgress 进度回调。传了就在 native 执行期间轮询共享内存的进度区
 *   （恢复的字节进度写在那里，和备份同一套布局）
 */
internal fun runSimpleNative(
    request: String,
    native: (ByteBuffer) -> Int,
    nativeError: String?,
    failText: String = "操作失败",
    onProgress: IBackupProgressCallback? = null,
): String {
    nativeError?.let { return failureJson(null, it) }
    val op = opOf(request)
    val startedAt = System.currentTimeMillis()

    return try {
        val shared = SharedMemory.create(RUSTIC_SHARED_MEMORY_NAME, RUSTIC_BUFFER_SIZE)
        try {
            val buffer = shared.mapReadWrite()
            writeFrameAt(buffer, 0, request)

            // 进度区由 native 侧写、这里轮询读。native 是阻塞调用，读必须在另一个线程
            val poller = onProgress?.let { RusticProgressPoller(buffer, it) }
            poller?.start()
            val code = try {
                native(buffer)
            } finally {
                poller?.stop()
            }
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
                failureJson(response, "$failText（code=$code）")
            }
        } finally {
            shared.close()
        }
    } catch (t: Throwable) {
        AppLog.e(TAG, "${failText}异常", t, op)
        failureJson(null, "${failText}异常：${t.message ?: t.javaClass.simpleName}")
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
