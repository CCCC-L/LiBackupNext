package com.cc.libackupnext.service.gateway

import com.cc.libackupnext.util.AppLog
import android.os.DeadObjectException
import kotlinx.coroutines.CancellationException

/**
 * 仓库调用的错误归一化：收敛成「一句话 + 可判定的断连」。
 *
 * 只有断连类原因才说「连不上」：原因由 Rust 侧给，不按「仓库是不是远程」猜，
 * 否则密码错、仓库缺件都会被说成网络问题。依赖全部构造注入，方便单独测。
 *
 * @param isRemote 决定断连时要不要停掉 rclone serve
 * @param onCredentialsStale 断连后让下次调用重新注入凭据
 * @param stopServe 停掉远程服务，让下一次请求重建连接
 */
internal class RemoteCallHandler(
    private val isRemote: () -> Boolean,
    private val onCredentialsStale: () -> Unit,
    private val stopServe: suspend () -> Unit,
) {

    private companion object {
        const val TAG = "Rustic"
    }

    /** rustic 失败收敛：原文进日志，UI 拿一句中文（或原文兜底）。 */
    fun backendFailure(
        label: String,
        failure: RusticFailure,
        cause: Throwable? = null
    ): BackendFailureException {
        val brief = BackendMessage.record(
            source = BackendSource.RUSTIC,
            raw = failure.message,
            error = failure.message,
            ok = false,
            label = label,
            reason = failure.reason
        )
        return BackendFailureException(
            message = brief.text.ifBlank { "操作失败" },
            detail = brief.detail,
            // 原文始终进 cause 链：UI 不显示它，但日志里要能查到
            cause = cause ?: BackendRawException(BackendSource.RUSTIC, failure.message)
        )
    }

    /** 把任意失败归一成对外语义；本地仓库没有「重连」概念，包装只会掩盖原因。 */
    suspend fun failure(t: Throwable): Throwable {
        // 已经是断连语义（含 RemoteServeException：serve 起不来，文案由 RcloneClient 定好）
        if (t is CancellationException || BackendErrors.isConnectionLost(t)) return t

        // root 服务死了不是网络问题：下次调用会重绑并重新注入凭据，不标断连，
        // 免得一笔临时 IPC 失败把整批停掉
        if (isBinderDeath(t)) {
            AppLog.w(TAG, "root 服务在调用中死亡", t)
            onCredentialsStale()
            return IllegalStateException("root 服务已退出，请重试", t)
        }

        // 部分异常没有 message（NPE / 解析异常）：至少把异常类型记进原文
        val raw = t.message?.takeIf { it.isNotBlank() } ?: t.toString()
        return interrupted(RusticFailure(raw), t)
    }

    /**
     * 所有仓库调用都过这里。catch 里要调 suspend 的 [failure]，所以用 try/catch。
     * @param retryOnDeath 只给只读调用：binder 死亡时重放无副作用；写操作不能重放。
     */
    suspend fun <T> call(
        retryOnDeath: Boolean = false,
        block: suspend () -> T,
    ): T = try {
        block()
    } catch (t: Throwable) {
        if (retryOnDeath && isBinderDeath(t)) {
            AppLog.w(TAG, "root 服务在调用中死亡，重绑后重试一次", t)
            // 不用手动重绑：block 里的 getService() 会拿到新 binder 并重新注入凭据
            try {
                block()
            } catch (t2: Throwable) {
                throw failure(t2)
            }
        } else {
            throw failure(t)
        }
    }

    /** root 侧把失败包成响应 JSON 返回、不抛异常，这些软错误同样要归一化。 */
    suspend fun error(failure: RusticFailure): Throwable = interrupted(failure, null)

    /** 断连类原因 + 远程仓库 → 断连；其余原样保留原因（message 就是具体原因）。 */
    private suspend fun interrupted(failure: RusticFailure, cause: Throwable?): Throwable {
        val concise = backendFailure(
            label = if (cause == null) "rusticFailure" else "remoteFailure",
            failure = failure,
            cause = cause
        )
        if (!failure.transport || !isRemote()) return concise
        return stopAndWrap(concise.message.orEmpty(), concise.detail, concise)
    }

    /** 断连收尾：日志 + 让下次重新注入凭据 + 停掉旧 serve。 */
    private suspend fun stopAndWrap(
        message: String,
        detail: String?,
        cause: Throwable
    ): Throwable {
        AppLog.e(TAG, "远程仓库连接中断：$message", cause)
        onCredentialsStale()
        // stopServe() 是 suspend：吞掉它自己的异常不能用 runCatching
        try {
            stopServe()
        } catch (stopError: Throwable) {
            AppLog.w(TAG, "停止远程服务失败", stopError)
        }
        return RemoteInterruptedException(message, detail, cause)
    }

    /** binder 事务没落地（目标进程已死）。沿 cause 链找，root 侧可能包过一层。 */
    private fun isBinderDeath(t: Throwable): Boolean =
        generateSequence(t) { it.cause }.any { it is DeadObjectException }
}
