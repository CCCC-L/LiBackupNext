package com.cc.libackupnext.service.gateway

import com.cc.libackupnext.service.gateway.BackendMessage
import com.cc.libackupnext.service.gateway.BackendRawException
import com.cc.libackupnext.service.gateway.BackendSource
import com.cc.libackupnext.util.AppLog
import android.os.DeadObjectException
import kotlinx.coroutines.CancellationException

/**
 * 一次仓库调用的错误归一化：把底层各种异常收敛成「用户能看的一句话 + 可判定的断连」。
 *
 * 从 `RusticClient` 里搬出来单独成类，因为它和「访问哪个仓库、发什么命令」无关：
 * 它只负责**失败长什么样**。这样换后端实现时不用重写，也能用假的
 * `isRemote` / `stopServe` 单独测。
 *
 * 依赖全部构造注入（函数类型），不读全局单例 —— 这是它能被测试的前提。
 *
 * @param isRemote 当前仓库是不是远程（决定断连时要不要停掉 rclone serve）
 * @param onCredentialsStale 断连后通知上层「下次调用重新注入凭据」
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

    /**
     * rustic 失败的统一收敛：完整原文进日志，UI 只显示底层 message。
     */
    fun backendFailure(
        label: String,
        raw: String,
        cause: Throwable? = null
    ): IllegalStateException {
        val brief = BackendMessage.record(
            source = BackendSource.RUSTIC,
            raw = raw,
            error = raw,
            ok = false,
            label = label
        )
        return IllegalStateException(
            brief.text.ifBlank { "操作失败" },
            cause ?: BackendRawException(BackendSource.RUSTIC, raw)
        )
    }

    /**
     * 把任意失败归一成对外语义：远程不可达统一换成
     * [BackendErrors.REMOTE_INTERRUPTED_MESSAGE]，并停掉旧的 serve。
     *
     * 本地仓库失败原样返回 —— 它没有「重连」这个概念，包装只会掩盖原因。
     */
    suspend fun failure(t: Throwable): Throwable {
        if (t is CancellationException || BackendErrors.isConnectionLost(t)) return t

        // 部分异常没有 message（NPE / 解析异常）：至少把异常类型记进原文
        val raw = t.message?.takeIf { it.isNotBlank() } ?: t.toString()
        val concise = backendFailure("remoteFailure", raw, t)
        if (!isRemote()) return concise

        // 原始异常的整条 Caused by 链仍写一份；正文带原文，完整原文另见 RusticResponse
        AppLog.e(TAG, "远程仓库连接中断：${concise.message}", t)
        onCredentialsStale()
        // stopServe() 是 suspend：用 try/catch 吞掉它自己的异常，不能用 runCatching 包
        try {
            stopServe()
        } catch (stopError: Throwable) {
            AppLog.w(TAG, "停止远程服务失败", stopError)
        }
        return IllegalStateException(BackendErrors.REMOTE_INTERRUPTED_MESSAGE, concise)
    }

    /**
     * 所有访问仓库的调用都过这里：远程失败统一换成
     * [BackendErrors.REMOTE_INTERRUPTED_MESSAGE]，让 UI 认得出断连、批量能停批。
     *
     * block 必须是 suspend；catch 里要调 suspend 的 [failure]（停 serve），
     * 所以用 try/catch 而不是 runCatching。
     *
     * @param retryOnDeath 只在**只读**调用上打开。`:root` 被系统收走时，正在飞的 binder
     *   事务会抛 [DeadObjectException]；只读操作重放一次没有副作用，用户就不该看到这个错误。
     *   备份 / 恢复 / 删除 / prune 不开：进程可能死在操作中途，重放是否安全要按业务语义判断。
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

    /**
     * root 侧把 rustic 的失败包成 error 字符串 / `ok=false` 返回，并不抛异常。
     * 这些「软错误」同样要归一化，否则断连时 UI 认不出、批量不会停批。
     */
    suspend fun error(message: String): Throwable =
        failure(IllegalStateException(message.ifBlank { "仓库操作失败" }))

    /** binder 事务没落地（目标进程已死）。沿 Caused by 链找，root 侧可能把异常包过一层。 */
    private fun isBinderDeath(t: Throwable): Boolean =
        generateSequence(t) { it.cause }.any { it is DeadObjectException }
}
