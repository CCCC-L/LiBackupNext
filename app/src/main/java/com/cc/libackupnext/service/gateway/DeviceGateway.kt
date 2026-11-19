package com.cc.libackupnext.service.gateway

import com.cc.libackupnext.data.AppInfo
import com.cc.libackupnext.data.UserAccount

/**
 * 本机设备操作：需要 root 权限、但**不碰仓库**的那些。
 *
 * 与仓库访问分开，是因为失败语义完全不同：仓库操作可能「远程断连」，
 * 设备操作只会「本机权限不足 / 系统拒绝」。混在一个接口里，
 * 调用方就没法只按需要处理一种失败。
 *
 * 覆盖面就是「这台设备上有什么、能对它做什么」：
 * 用户列表、已装应用、图标、强制停止 / 冻结 / 清数据 / 卸载，
 * 以及备份期间对目标进程的暂停与恢复。
 *
 * 加新能力往这里加，不要往 [RepoReader] / [RepoWriter] 里塞。
 */
interface DeviceGateway {

    // ==================== 系统设置 ====================

    /** 打开 / 关闭 Wi-Fi。 */
    suspend fun setWifiEnabled(enabled: Boolean): Result<Unit>

    // ==================== 应用查询 ====================

    /** 设备上的用户（主用户 / 分身 / 工作资料）。 */
    suspend fun getUsers(): List<UserAccount>

    /** 某个用户下已安装的应用。 */
    suspend fun getInstalledApps(userId: Int): List<AppInfo>

    /** 应用图标（PNG 字节）；跨用户或被隐藏的包只有 root 侧读得到。 */
    suspend fun getIcon(packageName: String, userId: Int): ByteArray?

    // ==================== 应用操作 ====================

    suspend fun forceStop(packageName: String, userId: Int)

    suspend fun freeze(packageName: String, userId: Int)

    suspend fun unfreeze(packageName: String, userId: Int)

    suspend fun clearData(packageName: String, userId: Int)

    suspend fun uninstall(packageName: String, userId: Int)

    // ==================== 备份期间的进程控制 ====================

    /**
     * 暂停应用的所有进程（SIGSTOP），返回是否真的暂停了。
     *
     * 系统数据 / 目录没有进程可停，返回 false。
     */
    suspend fun pause(packageName: String, userId: Int): Boolean

    /** 恢复被 [pause] 暂停的进程（SIGCONT）。 */
    suspend fun resume(packageName: String, userId: Int)

    /** 静默停掉应用（恢复前用）；失败不抛，调用方不关心。 */
    suspend fun stop(packageName: String, userId: Int)
}
