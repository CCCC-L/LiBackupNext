package com.cc.libackupnext.service

import android.app.ActivityManagerHidden
import android.app.ActivityThread
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.IPackageManager
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManagerHidden
import android.content.pm.UserInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.ServiceManager
import android.os.SystemClock
import android.os.UserManagerHidden
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.core.graphics.createBitmap
import com.cc.libackupnext.data.AppPrefs
import com.cc.libackupnext.entity.AppInfo
import com.cc.libackupnext.util.AppLog
import com.topjohnwu.superuser.ipc.RootService
import dev.rikka.tools.refine.Refine
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors

object AppManagerService {

    private const val TAG = "AppManagerService"

    /** 元数据列表头：读端看到 -1 说明服务端枚举失败，避免傻等 EOF。 */
    private const val COUNT_ERROR = -1

    /** 图标解码尺寸（px），与列表 48dp 的显示尺寸对齐，上限 192 防止内存浪费。 */
    private const val ICON_MIN_PX = 96
    private const val ICON_MAX_PX = 192

    private fun writeToPipe(block: (DataOutputStream) -> Unit): ParcelFileDescriptor? {
        val pipe = try {
            ParcelFileDescriptor.createPipe()
        } catch (e: IOException) {
            AppLog.e(TAG, "createPipe failed", e)
            return null
        }

        val readEnd = pipe[0]
        val writeEnd = pipe[1]

        Thread({
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { raw ->
                    DataOutputStream(BufferedOutputStream(raw, 16 * 1024)).use { out ->
                        block(out)
                        out.flush()
                    }
                }
            } catch (t: Throwable) {
                AppLog.e(TAG, "pipe write failed", t)
            }
        }, "PipeWriter").start()

        return readEnd
    }

    private fun <T> readFromPipe(pfd: ParcelFileDescriptor, block: (DataInputStream) -> T): T {
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { raw ->
            DataInputStream(BufferedInputStream(raw, 16 * 1024)).use { input ->
                return block(input)
            }
        }
    }

    class Service : RootService() {
        override fun onBind(intent: Intent): IBinder = Impl(applicationContext).apply { onBind() }
    }

    private class Impl(private val mContext: Context) : IAppManagerService.Stub() {

        private lateinit var mSystemContext: Context
        private lateinit var mUserManager: UserManagerHidden
        private lateinit var mPackageManager: PackageManager
        private lateinit var mPackageManagerHidden: PackageManagerHidden
        private lateinit var mPackageManagerService: IPackageManager
        private var mActivityManager: ActivityManagerHidden? = null

        fun onBind() {
            mSystemContext = ActivityThread.systemMain().systemContext
            mUserManager =
                Refine.unsafeCast(UserManagerHidden.get(mSystemContext.applicationContext))
            mPackageManager = mSystemContext.packageManager
            mPackageManagerHidden = Refine.unsafeCast(mPackageManager)
            mPackageManagerService = IPackageManager.Stub.asInterface(ServiceManager.getService("package"))
            // 拿不到也不影响列表/图标，操作时再兜底走命令
            mActivityManager = runCatching {
                Refine.unsafeCast<ActivityManagerHidden>(
                    mSystemContext.getSystemService(Context.ACTIVITY_SERVICE)
                )
            }.getOrNull()
        }

        override fun getUsers(): List<UserInfo> {
            return mUserManager.users
        }

        /**
         * 只传元数据，不传图标。
         */
        override fun getInstalledAppsAsUser(userId: Int): ParcelFileDescriptor? {
            return writeToPipe { out ->
                val infos = try {
                    // 列表里不展示当前应用自己
                    buildAppInfos(userId).filter { it.packageName != mContext.packageName }
                } catch (t: Throwable) {
                    Log.e(TAG, "buildAppInfos failed, userId=$userId", t)
                    null
                }

                if (infos == null) {
                    out.writeInt(COUNT_ERROR)
                    return@writeToPipe
                }

                out.writeInt(infos.size)
                for (a in infos) {
                    out.writeUTF(a.name)
                    out.writeUTF(a.packageName)
                    out.writeUTF(a.version)
                    out.writeLong(a.versionCode)
                    out.writeLong(a.installTime)
                    out.writeLong(a.updateTime)
                    out.writeInt(a.userId)
                    out.writeBoolean(a.isSystem)
                    out.writeBoolean(a.isFrozen)
                }
            }
        }

        private fun buildAppInfos(userId: Int): List<AppInfo> {
            val packages: List<PackageInfo> = mPackageManagerHidden.getInstalledPackagesAsUser(0, userId)
            if (packages.isEmpty()) return emptyList()

            // 读取名称需要加载每个应用的 Resources，是主要耗时点，并行跑一下。
            val threads = minOf(6, Runtime.getRuntime().availableProcessors().coerceAtLeast(1))
            if (packages.size < 24 || threads <= 1) {
                return packages.mapNotNull { toAppInfo(it, userId) }
            }

            val pool = Executors.newFixedThreadPool(threads)
            return try {
                pool.invokeAll(packages.map { pi -> Callable { toAppInfo(pi, userId) } })
                    .mapNotNull { future -> runCatching { future.get() }.getOrNull() }
            } finally {
                pool.shutdown()
            }
        }

        private fun toAppInfo(pi: PackageInfo, userId: Int): AppInfo? = runCatching {
            val ai = pi.applicationInfo ?: return@runCatching null
            AppInfo(
                name = ai.loadLabel(mPackageManager).toString(),
                packageName = pi.packageName,
                // 直接拼成展示用的字符串，UI 不用再判断
                version = "${pi.versionName ?: ""} (${pi.longVersionCode})",
                versionCode = pi.longVersionCode,
                installTime = pi.firstInstallTime,
                updateTime = pi.lastUpdateTime,
                userId = userId,
                isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                // 冻结 = 应用级 enabled 被置为 DISABLED
                isFrozen = !ai.enabled
            )
        }.getOrNull()

        /**
         * 单个图标，供主进程兜底使用。
         * root 进程可以读到被 hide / 其他用户下的应用，这是主进程拿不到图标时的最后保障。
         */
        override fun getIcon(packageName: String, userId: Int): ByteArray? {
            return try {
                // 带上 MATCH_UNINSTALLED_PACKAGES：被 pm hide / 对普通应用不可见的包
                // （典型如 KernelSU 管理器）只有这样才能取到
                val ai = mPackageManagerHidden
                    .getPackageInfoAsUser(packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES, userId)
                    ?.applicationInfo
                    ?: return null
                val drawable = mPackageManager.getApplicationIcon(ai) ?: return null
                drawableToIconBytes(drawable)
            } catch (t: Throwable) {
                Log.w(TAG, "getIcon($packageName, $userId) failed", t)
                null
            }
        }

        private fun drawableToIconBytes(drawable: Drawable): ByteArray {
            val size = iconSizePx()

            val bmp = if (drawable is BitmapDrawable && drawable.bitmap != null &&
                drawable.bitmap.width == size && drawable.bitmap.height == size
            ) {
                drawable.bitmap
            } else {
                createBitmap(size, size).also { b ->
                    val c = Canvas(b)
                    drawable.setBounds(0, 0, size, size)
                    drawable.draw(c)
                }
            }
            return ByteArrayOutputStream(4096).use { baos ->
                bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, 90, baos)
                baos.toByteArray()
            }
        }

        private fun iconSizePx(): Int {
            val px = (48 * mContext.resources.displayMetrics.density).toInt()
            return px.coerceIn(ICON_MIN_PX, ICON_MAX_PX)
        }

        // ==================== 应用操作 ====================

        override fun forceStop(packageName: String, userId: Int) {
            try {
                val am = mActivityManager ?: throw IllegalStateException("ActivityManager 不可用")
                am.forceStopPackageAsUser(packageName, userId)
                return
            } catch (t: Throwable) {
                Log.w(TAG, "forceStopPackageAsUser failed, fallback to am", t)
                throw IllegalStateException("强制停止失败")
            }
        }

        override fun pauseApp(packageName: String, userId: Int): Boolean {
            return freezeProcesses(packageName, userId, frozen = true) > 0
        }

        override fun resumeApp(packageName: String, userId: Int) {
            freezeProcesses(packageName, userId, frozen = false)
        }

        /**
         * 冻结 / 解冻这个包的所有进程。
         *
         * @return 实际处理成功的进程数
         */
        private fun freezeProcesses(packageName: String, userId: Int, frozen: Boolean): Int {
            if (packageName == mContext.packageName) return 0

            val uid = runCatching {
                mPackageManagerHidden.getPackageInfoAsUser(packageName, 0, userId)
                    .applicationInfo?.uid
            }.getOrNull() ?: return 0

            val signal = if (frozen) OsConstants.SIGSTOP else OsConstants.SIGCONT
            var count = 0
            for (pid in processIdsOf(uid, packageName)) {
                if (runCatching { Os.kill(pid, signal) }.isSuccess) count++
            }

            return count
        }


        /**
         * 找出「属于这个包」的进程。
         */
        private fun processIdsOf(uid: Int, packageName: String): List<Int> {
            val names = runCatching { File("/proc").list() }.getOrNull() ?: return emptyList()
            val pids = ArrayList<Int>()

            for (name in names) {
                val pid = name.toIntOrNull() ?: continue
                val realUid = runCatching {
                    File("/proc/$name/status").useLines { lines ->
                        lines.firstOrNull { it.startsWith("Uid:") }
                            ?.trim()
                            ?.split(Regex("\\s+"))
                            ?.getOrNull(1)
                            ?.toIntOrNull()
                    }
                }.getOrNull()
                if (realUid != uid) continue

                // cmdline 是 NUL 分隔的，取第一段就是进程名
                val cmdline = runCatching {
                    File("/proc/$name/cmdline").readText().substringBefore('\u0000')
                }.getOrDefault("")
                if (cmdline != packageName && !cmdline.startsWith("$packageName:")) continue

                pids.add(pid)
            }
            return pids
        }

        override fun freeze(packageName: String, userId: Int) {
            setAppEnabled(packageName, userId, PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
        }

        override fun unFreeze(packageName: String, userId: Int) {
            setAppEnabled(packageName, userId, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
        }

        /** 冻结 / 解冻：setApplicationEnabledSetting（隐藏 API，支持指定用户）。 */
        private fun setAppEnabled(packageName: String, userId: Int, newState: Int) {
            try {
                mPackageManagerService.setApplicationEnabledSetting(packageName, newState, 0, userId, null)
                return
            } catch (t: Throwable) {
                Log.w(TAG, "setApplicationEnabledSetting failed", t)
                val freeze = newState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                throw IllegalStateException(if (freeze) "冻结失败" else "解冻失败")
            }
        }

        override fun clearAppData(packageName: String, userId: Int) {
            // ActivityManager.clearApplicationUserData 只能作用于实例所属用户（root 服务是 user 0），
            // 其他用户必须走命令，否则会清错用户的数据
            if (userId == 0) {
                try {
                    if (mActivityManager?.clearApplicationUserData(packageName, null) == true) return
                } catch (t: Throwable) {
                    Log.w(TAG, "clearApplicationUserData failed", t)
                    throw IllegalStateException("清空数据失败")
                }
            }
        }

        override fun unInstall(packageName: String, userId: Int) {
            try {
                mPackageManagerHidden.deletePackageAsUser(packageName, null, 0, userId)
            } catch (t: Throwable) {
                Log.w(TAG, "deletePackageAsUser failed", t)
            }

            // 卸载是异步的，等状态真正变化后再返回，UI 刷新时拿到的才是准确状态
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (SystemClock.uptimeMillis() < deadline) {
                if (!isInstalledForUser(packageName, userId)) return
                SystemClock.sleep(120)
            }
            if (isInstalledForUser(packageName, userId)) {
                throw IllegalStateException("卸载失败")
            }
        }

        /** 该应用是否还安装在这个用户下（卸载轮询用，不对外暴露）。 */
        private fun isInstalledForUser(packageName: String, userId: Int): Boolean {
            return try {
                val ai = mPackageManagerHidden.getPackageInfoAsUser(
                    packageName,
                    PackageManager.MATCH_UNINSTALLED_PACKAGES,
                    userId
                )?.applicationInfo ?: return false
                // 已为该用户卸载（但数据还在）时 FLAG_INSTALLED 会被清掉
                (ai.flags and ApplicationInfo.FLAG_INSTALLED) != 0
            } catch (t: Throwable) {
                Log.w(TAG, "isInstalledForUser($packageName, $userId) failed", t)
                false
            }
        }
    }

    private val manager by lazy {
        RootServiceManager<IAppManagerService>(Service::class.java) {
            IAppManagerService.Stub.asInterface(it)
        }
    }

    suspend fun getService(): IAppManagerService = manager.getService()

    /** 断开并重启 root 服务进程（应用升级后用，避免跑旧代码）。 */
    fun restart() {
        manager.release()
    }

    /** 提前绑定 root 服务，避免真正拉列表时再等 su 握手。 */
    suspend fun warmUp() {
        runCatching { getService() }
            .onFailure { Log.w(TAG, "warmUp failed", it) }
    }

    suspend fun getUsers(): List<UserInfo> {
        return getService().users
    }

    suspend fun getInstalledAppsAsUser(userId: Int): List<AppInfo> {
        val pfd = getService().getInstalledAppsAsUser(userId)
            ?: throw IOException("root 服务没有返回数据流")

        return readFromPipe(pfd) { input ->
            val count = input.readInt()
            if (count == COUNT_ERROR) {
                throw IOException("root 服务读取应用列表失败")
            }
            if (count < 0) {
                throw IOException("非法的应用数量: $count")
            }
            if (count > 10_000) {
                throw IOException("应用数量异常: $count")
            }

            List(count) {
                AppInfo(
                    name = input.readUTF(),
                    packageName = input.readUTF(),
                    version = input.readUTF(),
                    versionCode = input.readLong(),
                    installTime = input.readLong(),
                    updateTime = input.readLong(),
                    userId = input.readInt(),
                    isSystem = input.readBoolean(),
                    isFrozen = input.readBoolean()
                )
            }
        }
    }

    /** 主进程拿不到图标时的兜底，失败返回 null。 */
    suspend fun getIcon(packageName: String, userId: Int): ByteArray? {
        return try {
            getService().getIcon(packageName, userId)
        } catch (t: Throwable) {
            Log.w(TAG, "getIcon($packageName, $userId) failed", t)
            null
        }
    }

    // ==================== 应用操作（走 root 服务） ====================

    suspend fun forceStop(packageName: String, userId: Int) {
        getService().forceStop(packageName, userId)
    }

    /**
     * 暂停应用：SIGSTOP 它在这个用户下的所有进程。
     *
     * @return 是否真的有进程被暂停（应用没在跑时返回 false）
     */
    suspend fun pauseApp(packageName: String, userId: Int): Boolean =
        runCatching { getService().pauseApp(packageName, userId) }.getOrDefault(false)

    /** 取消暂停：SIGCONT。失败忽略（进程可能已经退出了）。 */
    suspend fun resumeApp(packageName: String, userId: Int) {
        runCatching { getService().resumeApp(packageName, userId) }
    }

    /** 停止应用；应用没装或调用失败时忽略。 */
    suspend fun stopQuietly(packageName: String, userId: Int) {
        runCatching { getService().forceStop(packageName, userId) }
    }

    /**
     * 启动时兜底：上次备份如果被强杀，目标应用可能还停在 SIGSTOP 状态，
     * 这里按记录的清单恢复一遍，避免应用一直“卡死”。
     */
    suspend fun resumeAllPaused() {
        val keys = AppPrefs.pausedApps()
        if (keys.isEmpty()) return

        keys.forEach { key ->
            val parts = key.split('|')
            val packageName = parts.getOrNull(0).orEmpty()
            val userId = parts.getOrNull(1)?.toIntOrNull() ?: return@forEach
            if (packageName.isNotEmpty()) {
                runCatching { getService().resumeApp(packageName, userId) }
            }
        }
        AppPrefs.clearPausedApps()
    }

    suspend fun freeze(packageName: String, userId: Int) {
        getService().freeze(packageName, userId)
    }

    suspend fun unFreeze(packageName: String, userId: Int) {
        getService().unFreeze(packageName, userId)
    }

    suspend fun clearAppData(packageName: String, userId: Int) {
        getService().clearAppData(packageName, userId)
    }

    suspend fun unInstall(packageName: String, userId: Int) {
        getService().unInstall(packageName, userId)
    }
}
