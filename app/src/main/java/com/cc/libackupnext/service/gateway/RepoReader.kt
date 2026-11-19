package com.cc.libackupnext.service.gateway

import com.cc.libackupnext.data.AppBackupSummary
import com.cc.libackupnext.data.BackupEntry
import com.cc.libackupnext.data.BackupSnapshot
import com.cc.libackupnext.data.RepoOverview
import com.cc.libackupnext.data.SnapshotKind
import com.cc.libackupnext.data.AppInfo

/**
 * 仓库的**只读**访问。
 *
 * 单独切出来是为了让「只看不改」的调用方（列表页、详情页加载、图标补全）
 * 只依赖这一个面：它全部是幂等读，可以安全重试、可以并发、
 * 也不会因为调用方拿到它而误改仓库。
 *
 * 实现方负责把底层失败归一化（远程断连统一成
 * [BackendErrors.REMOTE_INTERRUPTED_MESSAGE]），调用方不需要认识 rustic / rclone 的异常。
 */
interface RepoReader {

    /**
     * 每个有备份的包：备份次数 + 最近一次备份 + 最新 apk 快照。
     *
     * 一次 IPC 拿全部，不拉全量快照列表（响应大小只跟应用数量有关）。
     * 没有配置仓库时返回空列表，不算失败。
     */
    suspend fun repoOverview(): Result<List<RepoOverview>>

    /** 某个包的备份行（按备份时间分组，最新的在前）。 */
    suspend fun loadEntries(packageName: String, appName: String): Result<List<BackupEntry>>

    /**
     * 仓库里有备份的所有应用（[AppInfo.onlyInBackup] 为 true）。
     *
     * @param overview 调用方已经查过概况就直接传，省一次 IPC
     */
    suspend fun backupApps(userId: Int, overview: List<RepoOverview>? = null): List<AppInfo>

    /**
     * 每个有备份的包的概况行（备份次数 + 最近一次备份），用于列表副标题与排序。
     *
     * @param appNames 包名 -> 应用名，用于拼每行标题
     */
    suspend fun backupOverview(
        appNames: Map<String, String>,
        overview: List<RepoOverview>? = null
    ): List<AppBackupSummary>

    /** [backupOverview] 的可失败版本：远程断连要以 failure 暴露，而不是退化成空列表。 */
    suspend fun backupOverviewResult(appNames: Map<String, String>): Result<List<AppBackupSummary>>

    /** 取某个包备份里的图标（应用没装时用）；失败即表示没有图标备份。 */
    suspend fun loadIcon(packageName: String): Result<ByteArray>

    /**
     * 系统数据（Wi-Fi / 联系人 / 通话记录 / 短信 / 彩信）各有多少条。
     *
     * 只是借 root 权限读本机数据，不碰仓库；读不到的类型不在结果里。
     */
    suspend fun systemDataCounts(): Map<SnapshotKind, Int>

    /**
     * 查询仓库里的快照。
     *
     * @param packageName 传 null 表示要全部快照
     */
    suspend fun listSnapshots(packageName: String? = null): Result<List<BackupSnapshot>>
}
