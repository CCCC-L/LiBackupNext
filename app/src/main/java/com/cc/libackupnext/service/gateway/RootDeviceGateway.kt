package com.cc.libackupnext.service.gateway

import com.cc.libackupnext.data.AppInfo
import com.cc.libackupnext.data.UserAccount
import com.cc.libackupnext.data.json.StatDirsRequest
import com.cc.libackupnext.data.json.StatDirsResponse
import com.cc.libackupnext.service.json.parseJson
import com.cc.libackupnext.service.json.toJsonText
import com.cc.libackupnext.service.root.AppManagerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [DeviceGateway] 的实现。
 *
 * 两套底层通道：
 * - Wi-Fi 开关走 rustic 服务的 binder（`IRusticService.setWifiEnabled`），
 *   因为它需要 uid 0 才能改系统设置，而那个 binder 已经在 `:root` 里；
 * - 应用管理走 [AppManagerService]（另一个 root 服务），它专门做包管理。
 *
 * 对调用方隐藏这个区别 —— 它们只知道「对设备做点什么」。
 */
internal class RootDeviceGateway(
    private val session: RusticSession,
) : DeviceGateway {

    override suspend fun setWifiEnabled(enabled: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val error = session.binder().setWifiEnabled(enabled)
                if (error.isNullOrBlank()) Result.success(Unit)
                else Result.failure(session.remote.backendFailure("setWifiEnabled", error.orEmpty()))
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }

    override suspend fun getUsers(): List<UserAccount> =
        AppManagerService.getUsers().map { UserAccount(it.id, it.name ?: "用户 ${it.id}") }

    override suspend fun getInstalledApps(userId: Int): List<AppInfo> =
        AppManagerService.getInstalledAppsAsUser(userId)

    override suspend fun getIcon(packageName: String, userId: Int): ByteArray? =
        AppManagerService.getIcon(packageName, userId)

    override suspend fun existingDirs(paths: List<String>): Result<Set<String>> =
        withContext(Dispatchers.IO) {
            if (paths.isEmpty()) return@withContext Result.success(emptySet())
            try {
                val raw = session.binder().statDirs(StatDirsRequest(paths).toJsonText())
                val response = raw.parseJson<StatDirsResponse>()
                if (!response.ok) {
                    Result.failure(
                        session.remote.backendFailure("statDirs", response.error.orEmpty())
                    )
                } else {
                    Result.success(response.existing.toSet())
                }
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }

    override suspend fun forceStop(packageName: String, userId: Int) =
        AppManagerService.forceStop(packageName, userId)

    override suspend fun freeze(packageName: String, userId: Int) =
        AppManagerService.freeze(packageName, userId)

    override suspend fun unfreeze(packageName: String, userId: Int) =
        AppManagerService.unFreeze(packageName, userId)

    override suspend fun clearData(packageName: String, userId: Int) =
        AppManagerService.clearAppData(packageName, userId)

    override suspend fun uninstall(packageName: String, userId: Int) =
        AppManagerService.unInstall(packageName, userId)

    override suspend fun pause(packageName: String, userId: Int): Boolean =
        AppManagerService.pauseApp(packageName, userId)

    override suspend fun resume(packageName: String, userId: Int) =
        AppManagerService.resumeApp(packageName, userId)

    override suspend fun stop(packageName: String, userId: Int) =
        AppManagerService.stopQuietly(packageName, userId)
}
