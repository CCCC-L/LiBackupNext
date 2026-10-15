package com.cc.libackupnext.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.IInterface
import android.os.Looper
import com.cc.libackupnext.App
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.milliseconds

/**
 * @author cong
 * @date 2026/10/18
 */
class RootServiceManager<T : IInterface>(
    private val serviceClass: Class<out RootService>,
    private val timeoutMs: Long = 20_000L,
    private val converter: (IBinder) -> T,
) {

    private val appContext: Context get() = App.application
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex: Mutex = Mutex()

    @Volatile
    private var service: T? = null

    @Volatile
    private var connection: ServiceConnection? = null
    private var reconnectJob: Job? = null
    private var attempts = 0

    suspend fun getService(): T {
        service?.let { return it }                       // 快速路径，零锁
        return mutex.withLock {
            service?.let { return@withLock it }          // 双重检查
            val binder = withTimeoutOrNull(timeoutMs.milliseconds) { awaitBinder() }
                ?: throw IllegalStateException("RootService 绑定超时 (${timeoutMs}ms)")
            converter(binder).also { service = it }
        }
    }

    private suspend fun awaitBinder(): IBinder = suspendCancellableCoroutine { cont ->
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, b: IBinder?) {
                if (b == null) {
                    cont.resumeWithException(IllegalStateException("onServiceConnected 返回 null binder"))
                } else {
                    attempts = 0
                    cont.resume(b)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                reconnect()
            }

            override fun onBindingDied(name: ComponentName?) {
                reconnect()
            }

            override fun onNullBinding(name: ComponentName?) {
                cont.resumeWithException(IllegalStateException("onNullBinding：设备无 root 或绑定被拒"))
            }
        }
        connection = conn

        cont.invokeOnCancellation {
            Handler(Looper.getMainLooper()).post { RootService.unbind(conn) }
            if (connection === conn) connection = null
        }

        val intent = Intent(appContext, serviceClass)
        Handler(Looper.getMainLooper()).post { RootService.bind(intent, conn) }
    }

    private fun reconnect() {
        service = null
        // 清理旧 connection
        connection?.let { c ->
            Handler(Looper.getMainLooper()).post { RootService.unbind(c) }
        }
        connection = null

        if (reconnectJob?.isActive == true) return

        reconnectJob = scope.launch {
            while (isActive) {
                delay((1_000L shl attempts.coerceAtMost(5)).coerceAtMost(30_000L).milliseconds)
                attempts++
                try {
                    getService()
                    return@launch                       // 重连成功，退出循环
                } catch (e: CancellationException) {
                    throw e                             // 外部取消，停止重连
                } catch (_: Exception) {
                    // 超时 / null binding 等，继续退避重试
                }
            }
        }
    }

    /** 主动断开，但 Manager 仍可再次 getService() 重连 */
    fun release() {
        service = null
        reconnectJob?.cancel()
        reconnectJob = null
        connection?.let { c ->
            Handler(Looper.getMainLooper()).post { RootService.unbind(c) }
        }
        connection = null
    }
}