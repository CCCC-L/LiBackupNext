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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * @author cong
 * @date 2026/10/18
 */

/**
 * root 服务的连接缓存：只负责「给一个能用的服务」，不负责「守着它不死」。
 *
 * `:root` 是 su 拉起的 app_process，AMS 不管、系统随时收走，所以不跟死亡对抗：
 * `onServiceDisconnected` 只标脏，下一次 [getService] 自己绑。一次绑定对应一个 [Conn]，
 * 整体替换而非就地改字段，迟到回调靠身份比对丢弃。
 *
 * 不设绑定超时：su 被拒走 [Conn.onNullBinding] 立刻抛，没 root 根本进不来这个应用。
 */
class RootServiceManager<T : IInterface>(
    private val serviceClass: Class<out RootService>,
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

    /** 拿一个可用的服务。缓存有效就直接返回，否则现绑。 */
    suspend fun getService(): T {
        aliveService()?.let { return it }
        return mutex.withLock {
            aliveService()?.let { return@withLock it }
            bind()
        }
    }

    /** 缓存里的服务还活着才返回。`isBinderAlive` 只是一次 ping，比等一次业务调用失败便宜。 */
    private fun aliveService(): T? =
        binding?.takeIf { it.binder.isBinderAlive }?.service

    /** 必须在 [mutex] 内调用。设备没 root 时 [Conn.onNullBinding] 会立刻抛，不会挂住。 */
    private suspend fun bind(): T {
        val conn = Conn()
        val binder = conn.await()
        val service = converter(binder)
        binding = Binding(conn, binder, service)
        return service
    }

    /**
     * 服务断开：标脏 + 退掉这条绑定。
     *
     * 只置空缓存不够：系统会自动重连到同一个 [Conn]，而它的 `result` 已完成，
     * 新 binder 没人接，libsu 里会留下一条永远不释放的绑定。
     */
    private fun invalidate(conn: Conn) {
        val current = binding ?: return
        // 旧 conn 的迟到回调：现在的绑定已经不是它了，忽略
        if (current.conn !== conn) return
        binding = null
        conn.abandon()

        when (val nth = disconnectGate.mark(System.currentTimeMillis())) {
            1 -> AppLog.w(TAG, "root 服务断开，已退掉旧绑定，下次调用自动重绑")
            else -> AppLog.d(TAG, "root 服务断开（同一次进程死亡的第 $nth 个回调，已合并）")
        }
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
            // 已经放弃的绑定又连上了：不退的话 libsu 里会留一条永远没人释放的绑定
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

    private val intent by lazy { Intent(appContext, serviceClass) }

    private companion object {
        const val TAG = "RootService"

        /** 一次 kill 的三个回调落在窗口内，只留第一条 WARN。 */
        const val DISCONNECT_WINDOW_MS = 1_000L

        /** companion 由三个服务共享，所以是进程级状态。 */
        val disconnectGate = DisconnectGate(DISCONNECT_WINDOW_MS)
    }
}
