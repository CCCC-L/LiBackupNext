package com.cc.libackupnext.service.root

import android.content.pm.UserInfo
import android.os.ParcelFileDescriptor
import android.util.Log
import com.cc.libackupnext.data.AppInfo
import com.cc.libackupnext.data.json.StatDirsRequest
import com.cc.libackupnext.data.json.StatDirsResponse
import com.cc.libackupnext.service.IAppManagerService
import com.cc.libackupnext.service.json.parseJson
import com.cc.libackupnext.service.json.toJsonText
import com.cc.libackupnext.util.AppLog
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException

/**
 * 主进程侧的应用管理入口：绑定 [AppManagerServer]、把 AIDL 调用收成 suspend。
 *
 * 和 [com.cc.libackupnext.service.rclone.RcloneClient] 一个角色 —— 上层（gateway /
 * usecase）只说「查应用、冻结、清数据」，不接触 binder、管道和超时。
 */
object AppManagerClient {

    private const val TAG = "AppManagerClient"

    private val manager by lazy {
        RootServiceManager<IAppManagerService>(AppManagerServer.Service::class.java) {
            IAppManagerService.Stub.asInterface(it)
        }
    }

    private suspend fun getService(): IAppManagerService = manager.getService()

    /** 提前绑定 root 服务，避免真正拉列表时再等 su 握手。 */
    suspend fun warmUp() {
        runCatching { getService() }
            .onFailure { AppLog.w(TAG, "warmUp failed", it) }
    }

    suspend fun getUsers(): List<UserInfo> = getService().users

    suspend fun getInstalledAppsAsUser(userId: Int): List<AppInfo> {
        val pfd = getService().getInstalledAppsAsUser(userId)
            ?: throw IOException("root 服务没有返回数据流")

        return readFromPipe(pfd) { input -> AppInfoPipe.read(input) }
    }

    /** 主进程拿不到图标时的兜底，失败返回 null。 */
    suspend fun getIcon(packageName: String, userId: Int): ByteArray? {
        return try {
            getService().getIcon(packageName, userId)
        } catch (t: Throwable) {
            Log.d(TAG, "getIcon($packageName, $userId) failed", t)
            null
        }
    }

    // ==================== 应用操作 ====================

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

    // ==================== 设备状态 ====================

    /** 批量判断目录是否存在，返回确实存在的那些；root 侧读失败时抛异常。 */
    suspend fun statDirs(paths: List<String>): Set<String> {
        val raw = getService().statDirs(StatDirsRequest(paths).toJsonText())
        val response = raw.parseJson<StatDirsResponse>()
        if (!response.ok) throw IllegalStateException(response.error ?: "判断目录是否存在失败")
        return response.existing.toSet()
    }

    /** 开关 Wi-Fi；失败抛异常，message 就是具体原因。 */
    suspend fun setWifiEnabled(enabled: Boolean) {
        val error = getService().setWifiEnabled(enabled)
        if (!error.isNullOrBlank()) throw IllegalStateException(error)
    }

    private fun <T> readFromPipe(pfd: ParcelFileDescriptor, block: (DataInputStream) -> T): T {
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { raw ->
            DataInputStream(BufferedInputStream(raw, 16 * 1024)).use { input ->
                return block(input)
            }
        }
    }
}
