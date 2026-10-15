package com.cc.libackupnext.ui.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Process
import android.util.Log
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.cc.libackupnext.App
import com.cc.libackupnext.data.AppPrefs
import com.cc.libackupnext.service.AppManagerService
import com.cc.libackupnext.service.RusticService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/**
 * 应用图标仓库：按需加载 + 内存 LRU 缓存 + 并发去重。
 *
 * 加载顺序：
 * 1. 本进程 [android.content.pm.PackageManager.getApplicationIcon]（最快，无 IPC）
 * 2. 本进程失败 / 跨用户时，回落到 root 服务（能读到被 hide 的包，例如 KernelSU 管理器）
 *
 * 注意：图标绝不放在主线程加载，也不随应用列表一起传输。
 */
object AppIconStore {

    private const val TAG = "AppIconStore"

    /** 当前进程所在用户，用于判断能否直接用本进程的 PackageManager 读图标。 */
    private val currentUserId: Int = Process.myUid() / 100_000

    private val iconSizePx: Int by lazy {
        val density = App.application.resources.displayMetrics.density
        (48 * density).toInt().coerceIn(96, 192)
    }

    private val maxCacheBytes: Int by lazy {
        (Runtime.getRuntime().maxMemory() / 8).coerceIn(8L * 1024 * 1024, 48L * 1024 * 1024).toInt()
    }

    private val cache = object : LruCache<String, Bitmap>(maxCacheBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 限制同时解码的数量，避免快速滚动时一次性拉起几百个解码任务。 */
    private val permits = Semaphore(4)

    private val inflight = ConcurrentHashMap<String, Deferred<Bitmap?>>()

    /** 确定拿不到图标的 key，避免反复重试。 */
    private val missing = ConcurrentHashMap.newKeySet<String>()

    private fun keyOf(packageName: String, userId: Int) = "$userId:$packageName"

    fun cached(packageName: String, userId: Int): Bitmap? = cache.get(keyOf(packageName, userId))

    /** 挂起加载；同一个图标只会真正加载一次。 */
    suspend fun load(packageName: String, userId: Int): Bitmap? {
        val key = keyOf(packageName, userId)
        cache.get(key)?.let { return it }
        if (missing.contains(key)) return null

        val deferred = inflight.computeIfAbsent(key) {
            scope.async {
                val bitmap = try {
                    loadBitmap(packageName, userId)
                } catch (t: Throwable) {
                    Log.w(TAG, "load failed: $key", t)
                    null
                }
                if (bitmap != null) cache.put(key, bitmap) else missing.add(key)
                bitmap
            }
        }

        return try {
            deferred.await()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            null
        } finally {
            if (deferred.isCompleted) inflight.remove(key, deferred)
        }
    }

    /** 清空负缓存，重新加载列表时调用。 */
    fun invalidateMissing() {
        missing.clear()
    }

    /**
     * 单个应用的图标重新加载。
     *
     * 恢复把它装上之后调用：之前取不到图标会记在 [missing] 里，
     * 不清掉的话即使应用装上了，图标也一直是首字母占位块。
     */
    fun invalidate(packageName: String, userId: Int) {
        val key = keyOf(packageName, userId)
        cache.remove(key)
        missing.remove(key)
        inflight.remove(key)
    }

    /** 清空全部图标缓存（切换「从仓库取图标」这类设置后调用）。 */
    fun clearAll() {
        cache.evictAll()
        missing.clear()
    }

    // ==================== 备份里的图标 ====================

    private fun backupKey(packageName: String) = "backup:$packageName"

    fun cachedBackup(packageName: String): Bitmap? {
        if (!AppPrefs.fetchIconFromRepo) return null
        return cache.get(backupKey(packageName))
    }

    /** 从 rustic 的 `<包名>:icon` 快照里取图标（应用没装时用）。 */
    suspend fun loadFromBackup(packageName: String): Bitmap? {
        // 设置里关掉之后就不再去仓库里取
        if (!AppPrefs.fetchIconFromRepo) return null

        val key = backupKey(packageName)
        cache.get(key)?.let { return it }
        if (missing.contains(key)) return null

        // 同一个图标只导出一次：列表和详情页同时请求时复用同一个任务
        val deferred = inflight.computeIfAbsent(key) {
            scope.async {
                val bitmap = permits.withPermit {
                    RusticService.loadIcon(packageName)
                        .getOrNull()?.let { bytes ->
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        }
                }
                if (bitmap != null) cache.put(key, bitmap) else missing.add(key)
                bitmap
            }
        }

        return try {
            deferred.await()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            null
        } finally {
            if (deferred.isCompleted) inflight.remove(key, deferred)
        }
    }

    private suspend fun loadBitmap(packageName: String, userId: Int): Bitmap? {
        val pm = App.application.packageManager

        if (userId == currentUserId) {
            runCatching { pm.getApplicationIcon(packageName) }
                .getOrNull()
                ?.let { return it.toIconBitmap() }
        }

        // 兜底：root 进程读取（跨用户、被 hide 的包）
        permits.withPermit {
            val bytes = AppManagerService.getIcon(packageName, userId)
            if (bytes != null && bytes.isNotEmpty()) {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { return it }
            }
        }

        if (userId != currentUserId) {
            runCatching { pm.getApplicationIcon(packageName) }
                .getOrNull()
                ?.let { return it.toIconBitmap() }
        }

        return null
    }

    private fun Drawable.toIconBitmap(): Bitmap {
        val size = iconSizePx
        if (this is BitmapDrawable && bitmap != null &&
            bitmap.width == size && bitmap.height == size
        ) {
            return bitmap
        }
        val target = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(target)
        setBounds(0, 0, size, size)
        draw(canvas)
        return target
    }
}

/**
 * Compose 侧入口：图标在后台加载完成后自动重组。
 * 返回 null 时调用方展示占位图标。
 */
/** 备份里的图标（应用未安装时用）。 */
@Composable
fun rememberBackupIcon(packageName: String): ImageBitmap? {
    var bitmap by remember(packageName) {
        mutableStateOf(AppIconStore.cachedBackup(packageName))
    }

    LaunchedEffect(packageName) {
        if (bitmap == null) {
            bitmap = AppIconStore.loadFromBackup(packageName)
        }
    }

    return remember(bitmap) { bitmap?.asImageBitmap() }
}

@Composable
fun rememberAppIcon(packageName: String, userId: Int): ImageBitmap? {
    var bitmap by remember(packageName, userId) {
        mutableStateOf(AppIconStore.cached(packageName, userId))
    }

    LaunchedEffect(packageName, userId) {
        if (bitmap == null) {
            bitmap = AppIconStore.load(packageName, userId)
        }
    }

    return remember(bitmap) { bitmap?.asImageBitmap() }
}
