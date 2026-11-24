package com.cc.libackupnext.service.gateway

import com.cc.libackupnext.data.AppInfo
import com.cc.libackupnext.data.UserAccount
import com.cc.libackupnext.service.root.AppManagerClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [DeviceGateway] 的实现。
 *
 * 全部走 [AppManagerClient]：它是专门做「对设备做点什么」的 root 服务 ——
 * 包管理、目录探测、Wi-Fi 开关都在它上面。这些都不需要仓库凭据，所以不必先绑 rustic，
 * 也不必为一个 Wi-Fi 开关去等 su 握手加注入配置。
 *
 * 对调用方隐藏这个区别 —— 它们只知道「对设备做点什么」。
 */
internal class RootDeviceGateway : DeviceGateway {

    override suspend fun setWifiEnabled(enabled: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                AppManagerClient.setWifiEnabled(enabled)
                Result.success(Unit)
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }

    override suspend fun getUsers(): List<UserAccount> =
        AppManagerClient.getUsers().map { UserAccount(it.id, it.name ?: "用户 ${it.id}") }

    override suspend fun getInstalledApps(userId: Int): List<AppInfo> =
        AppManagerClient.getInstalledAppsAsUser(userId)

    override suspend fun getIcon(packageName: String, userId: Int): ByteArray? =
        AppManagerClient.getIcon(packageName, userId)

    override suspend fun existingDirs(paths: List<String>): Result<Set<String>> =
        withContext(Dispatchers.IO) {
            if (paths.isEmpty()) return@withContext Result.success(emptySet())
            try {
                Result.success(AppManagerClient.statDirs(paths))
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }

    override suspend fun forceStop(packageName: String, userId: Int) =
        AppManagerClient.forceStop(packageName, userId)

    override suspend fun freeze(packageName: String, userId: Int) =
        AppManagerClient.freeze(packageName, userId)

    override suspend fun unfreeze(packageName: String, userId: Int) =
        AppManagerClient.unFreeze(packageName, userId)

    override suspend fun clearData(packageName: String, userId: Int) =
        AppManagerClient.clearAppData(packageName, userId)

    override suspend fun uninstall(packageName: String, userId: Int) =
        AppManagerClient.unInstall(packageName, userId)

    override suspend fun pause(packageName: String, userId: Int): Boolean =
        AppManagerClient.pauseApp(packageName, userId)

    override suspend fun resume(packageName: String, userId: Int) =
        AppManagerClient.resumeApp(packageName, userId)

    override suspend fun stop(packageName: String, userId: Int) =
        AppManagerClient.stopQuietly(packageName, userId)
}
