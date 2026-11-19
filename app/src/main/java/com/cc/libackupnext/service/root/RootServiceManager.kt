package com.cc.libackupnext.service.root

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.IInterface
import android.os.Looper
import com.cc.libackupnext.App
import com.cc.libackupnext.util.AppLog
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

/**
 * @author cong
 * @date 2026/10/18
 */

/**
 * root 服务的连接缓存：**只负责「给一个能用的服务」，不负责「守着它不死」**。
 *
 * 设计取舍：
 * 1. `:root` 是 su 拉起的 app_process（uid 0），AMS 不管它，系统随时会收走。
 *    所以这里不跟死亡对抗，只保证「下一次调用能拿到新的」——死亡的成本摊到下一次调用上。
 * 2. 没有重连循环。`onServiceDisconnected` 只把缓存标脏，下一次 [getService] 自己会绑。
 *    这样既没有退避导致的长空窗，也不会有「自己 unbind 触发重连」的自激
 *    （libsu 的 `unbind` 会回调 `onServiceDisconnected`）。
 * 3. 一次绑定对应一个 [Conn]，整体替换而不是就地改字段。旧 conn 的迟到回调靠身份比对丢掉，
 *    不需要额外的代次号。
 * 4. 绑定超时必须真正退掉。libsu 的 `unbind` 对「还没进 connections 表的 conn」是空操作，
 *    所以迟到连上的 conn 要在 [Conn.onServiceConnected] 里自己退，否则会留下永久悬挂绑定
 *    （refCount 永远不减，服务再也销毁不掉）。
 */
class RootServiceManager<T : IInterface>(
    private val serviceClass: Class<out RootService>,
    private val bindTimeoutMs: Long = DEFAULT_BIND_TIMEOUT_MS,
    private val converter: (IBinder) -> T,
) {

    private val appContext: Context get() = App.application
    private val mutex: Mutex = Mutex()

    /** 一次成功的绑定。换绑整体替换，绝不就地改字段。 */
    private class Binding<T>(
        val conn: ServiceConnection,
        val binder: IBinder,
        val service: T,
    )

    @Volatile
    private var binding: Binding<T>? = null

    /**
     * 拿一个可用的服务。缓存有效就直接返回，否则现绑。
     *
     * 超时失败会立刻重试一次：root 进程刚被系统收走时，第一次绑往往正撞在重启窗口上。
     * 「设备没有 root」这类确定性失败不重试，直接抛。
     */
    suspend fun getService(): T {
        aliveService()?.let { return it }
        return mutex.withLock {
            aliveService()?.let { return@withLock it }
            try {
                bind()
            } catch (e: CancellationException) {
                throw e
            } catch (_: BindTimeoutException) {
                delay(RETRY_DELAY_MS.milliseconds)
                bind()
            }
        }
    }

    /** 缓存里的服务还活着才返回。`isBinderAlive` 只是一次 ping，比等一次业务调用失败便宜。 */
    private fun aliveService(): T? =
        binding?.takeIf { it.binder.isBinderAlive }?.service

    /** 必须在 [mutex] 内调用。 */
    private suspend fun bind(): T {
        val conn = Conn()
        val binder = withTimeoutOrNull(bindTimeoutMs.milliseconds) { conn.await() }
            ?: run {
                conn.abandon()
                throw BindTimeoutException(bindTimeoutMs)
            }
        val service = converter(binder)
        binding = Binding(conn, binder, service)
        return service
    }

    /**
     * 服务断开：标脏 + **退掉这条绑定**。
     *
     * 只置空缓存是不够的 —— 系统对 `onServiceDisconnected` 的处理是「绑定还在，服务没了」，
     * 会自动尝试重连到同一个 [Conn]；而那个 Conn 的 `result` 已经完成，新的 binder 没人接，
     * 于是 libsu 里留下一条永远不释放的绑定（refCount 不减，服务再也销毁不掉）。
     *
     * 退掉之后 `onServiceDisconnected` 会再回调一次，靠 `abandoned` 拦住，不会递归。
     */
    private fun invalidate(conn: Conn) {
        val current = binding ?: return
        // 旧 conn 的迟到回调：现在的绑定已经不是它了，忽略
        if (current.conn !== conn) return
        binding = null
        conn.abandon()
        AppLog.w(TAG, "root 服务断开，已退掉旧绑定，下次调用自动重绑")
    }

    private inner class Conn : ServiceConnection {

        private val result = CompletableDeferred<IBinder>()

        /** 我们自己不要这条绑定了。用来把「主动退」和「意外死」分开。 */
        @Volatile
        private var abandoned = false

        init {
            Handler(Looper.getMainLooper()).post { RootService.bind(intent, this) }
        }

        suspend fun await(): IBinder = result.await()

        /** 放弃这条绑定：退掉它，并拦住迟到的 [onServiceConnected]。 */
        fun abandon() {
            abandoned = true
            Handler(Looper.getMainLooper()).post { RootService.unbind(this) }
        }

        override fun onServiceConnected(name: ComponentName?, b: IBinder?) {
            // 超时之后才连上：不退的话 libsu 里会留一条永远没人释放的绑定
            if (abandoned || b == null) {
                if (b != null) Handler(Looper.getMainLooper()).post { RootService.unbind(this) }
                result.completeExceptionally(IllegalStateException("绑定已放弃"))
                return
            }
            result.complete(b)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // 自己退的，不是意外
            if (abandoned) return
            invalidate(this)
        }

        override fun onNullBinding(name: ComponentName?) {
            result.completeExceptionally(IllegalStateException("设备无 root 或绑定被拒"))
        }
    }

    /** 绑定超时：root 进程可能刚被收走，值得立刻再试一次。 */
    private class BindTimeoutException(timeoutMs: Long) :
        IllegalStateException("root 服务绑定超时（${timeoutMs}ms）")

    private val intent by lazy { Intent(appContext, serviceClass) }

    private companion object {
        const val TAG = "RootService"

        /**
         * 8 秒足够覆盖「su 握手 + app_process 启动 + broadcast 回连」。
         * 原来 20 秒配合最长 30 秒退避，一次失败能把界面卡 100 秒。
         */
        const val DEFAULT_BIND_TIMEOUT_MS = 8_000L

        /** 超时后立刻重试的间隔：只让出一次调度，不做指数退避。 */
        const val RETRY_DELAY_MS = 300L
    }
}
