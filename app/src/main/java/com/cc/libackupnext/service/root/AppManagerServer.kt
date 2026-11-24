package com.cc.libackupnext.service.root

import android.app.ActivityManagerHidden
import android.app.ActivityThread
import android.content.Context
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
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.ServiceManager
import android.os.SystemClock
import android.os.UserManagerHidden
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.core.graphics.createBitmap
import com.cc.libackupnext.data.AppInfo
import com.cc.libackupnext.data.json.OkResponse
import com.cc.libackupnext.data.json.StatDirsRequest
import com.cc.libackupnext.data.json.StatDirsResponse
import com.cc.libackupnext.service.IAppManagerService
import com.cc.libackupnext.service.json.parseJson
import com.cc.libackupnext.service.json.toJsonText
import com.cc.libackupnext.util.AppLog
import dev.rikka.tools.refine.Refine
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * `:root` 侧的应用管理服务：包枚举 / 图标 / 冻结 / 卸载 / 清数据 / 目录探测 / Wi-Fi。
 *
 * 主进程侧的入口是 [AppManagerClient]，那边负责绑定、超时和异常归一；这里只管
 * 「拿到 binder 之后做什么」。两个文件之间唯一的协议是 `IAppManagerService`
 * 和 [AppInfoPipe]（后者定义应用列表的管道格式）。
 */
object AppManagerServer {

    private const val TAG = "AppManagerServer"

    /** 图标解码尺寸（px），与列表 48dp 的显示尺寸对齐，上限 192 防止内存浪费。 */
    private const val ICON_MIN_PX = 96
    private const val ICON_MAX_PX = 192

    class Service : RootBinderService() {
        override fun createBinder(context: Context): IBinder = Impl(context).apply { initialize() }
    }

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

    private class Impl(private val mContext: Context) : IAppManagerService.Stub() {

        private lateinit var mSystemContext: Context
        private lateinit var mUserManager: UserManagerHidden
        private lateinit var mPackageManager: PackageManager
        private lateinit var mPackageManagerHidden: PackageManagerHidden
        private lateinit var mPackageManagerService: IPackageManager
        private var mActivityManager: ActivityManagerHidden? = null

        fun initialize() {
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
                    AppLog.e(TAG, "buildAppInfos failed, userId=$userId", t)
                    null
                }

                if (infos == null) {
                    out.writeInt(AppInfoPipe.COUNT_ERROR)
                    return@writeToPipe
                }

                AppInfoPipe.write(out, infos)
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
                // getApplicationIcon(ApplicationInfo) 返回非空 Drawable；拿不到会抛异常（下面 catch）
                val drawable = mPackageManager.getApplicationIcon(ai)
                drawableToIconBytes(drawable)
            } catch (t: Throwable) {
                Log.d(TAG, "getIcon($packageName, $userId) failed", t)
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
            val viaBinder = runCatching {
                mActivityManager?.forceStopPackageAsUser(packageName, userId)
                    ?: error("ActivityManager 不可用")
            }
            if (viaBinder.isSuccess) return

            // 拿不到 ActivityManager 或 binder 调用被拒，都退回命令。
            // am force-stop 成功时没有输出，只能靠退出码判成败。
            AppLog.w(TAG, "forceStopPackageAsUser failed, fallback to am", viaBinder.exceptionOrNull())
            runShellChecked("am force-stop --user $userId $packageName", "强制停止失败")
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
                AppLog.w(TAG, "setApplicationEnabledSetting failed", t)
                val freeze = newState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                throw IllegalStateException(if (freeze) "冻结失败" else "解冻失败")
            }
        }

        override fun clearAppData(packageName: String, userId: Int) {
            // 统一走 pm：ActivityManager.clearApplicationUserData 只能作用于服务自身所在的用户，
            // 而且它连「包不存在」也返回 true，看不出失败。pm clear 失败时退出码同样是 0，
            // 所以判据是输出里必须出现 Success。
            runShellChecked(
                command = "pm clear --user $userId $packageName",
                failText = "清空数据失败",
                expect = "Success"
            )
        }

        override fun unInstall(packageName: String, userId: Int) {
            try {
                mPackageManagerHidden.deletePackageAsUser(packageName, null, 0, userId)
            } catch (t: Throwable) {
                AppLog.w(TAG, "deletePackageAsUser failed", t)
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
                Log.d(TAG, "isInstalledForUser($packageName, $userId) failed", t)
                false
            }
        }

        // ==================== 设备状态 ====================

        /**
         * 批量判断目录是否存在。
         *
         * 主进程（普通 uid）只看得到自己用户的空间，别的用户的目录只能由 root 判；
         * 传进来的路径是物理路径，`File.isDirectory` 直接读 /data/media/<userId>/…。
         */
        override fun statDirs(request: String): String = try {
            val paths = request.parseJson<StatDirsRequest>().paths
            val existing = paths.filter { it.isNotBlank() && File(it).isDirectory }
            StatDirsResponse(ok = true, existing = existing).toJsonText()
        } catch (t: Throwable) {
            AppLog.e(TAG, "判断目录是否存在失败", t)
            OkResponse(ok = false, error = t.message ?: "判断目录是否存在失败").toJsonText()
        }

        @Suppress("DEPRECATION")
        override fun setWifiEnabled(enabled: Boolean): String = try {
            val manager = mContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return "拿不到 WifiManager"

            when {
                manager.isWifiEnabled == enabled -> ""
                manager.setWifiEnabled(enabled) -> ""
                else -> "系统拒绝了这次操作"
            }
        } catch (t: Throwable) {
            AppLog.w(TAG, "切换 Wi-Fi 失败", t)
            t.message ?: "切换 Wi-Fi 失败"
        }
    }
}
