package com.cc.libackupnext.service.gateway

import android.util.Log
import com.cc.libackupnext.data.AppBackupSummary
import com.cc.libackupnext.data.prefs.RepoPrefs
import com.cc.libackupnext.data.BackupEntry
import com.cc.libackupnext.data.BackupSnapshot
import com.cc.libackupnext.data.MediaPath
import com.cc.libackupnext.data.RepoOverview
import com.cc.libackupnext.data.SnapshotKind
import com.cc.libackupnext.data.SnapshotMeta
import com.cc.libackupnext.data.kind
import com.cc.libackupnext.data.json.ExportSnapshotRequest
import com.cc.libackupnext.data.json.ListSnapshotsRequest
import com.cc.libackupnext.data.json.ListSnapshotsResponse
import com.cc.libackupnext.data.json.OverviewResponse
import com.cc.libackupnext.data.json.SystemDataCountsResponse
import com.cc.libackupnext.data.AppInfo
import com.cc.libackupnext.service.gateway.RusticSession.Companion.EMPTY_REQUEST
import com.cc.libackupnext.service.gateway.RusticSession.Companion.TAG
import com.cc.libackupnext.service.json.parseJson
import com.cc.libackupnext.service.json.toJsonText
import com.cc.libackupnext.service.root.sysdata.SystemDataExporter
import com.cc.libackupnext.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 仓库的只读访问实现。
 *
 * 每个方法只做一件事：拼一个请求 → 发出去 → 把响应翻成数据模型。
 * 不判断「该不该查」、不做业务收尾 —— 那是 usecase 的事。
 */
internal class RusticReaderGateway(
    private val session: RusticSession,
    private val repoPrefs: RepoPrefs,
) : RepoReader {

    override suspend fun repoOverview(): Result<List<RepoOverview>> = withContext(Dispatchers.IO) {
        if (!repoPrefs.hasRepository()) return@withContext Result.success(emptyList())

        try {
            val response = session.remote.call(retryOnDeath = true) {
                session.service().overview(EMPTY_REQUEST)
            }.parseJson<OverviewResponse>()

            if (!response.ok) {
                val message = response.error.orEmpty().ifBlank { "读取备份概况失败" }
                AppLog.e("备份概况", message)
                // 远程软错误归一一次（停掉旧 serve，让下次请求重建连接），
                // 作为 Result.failure 交给 UI 决定怎么提示；本地读失败仍退化为空列表
                if (repoPrefs.isRemote) {
                    return@withContext Result.failure(
                        session.remote.failure(IllegalStateException(message))
                    )
                }
                return@withContext Result.success(emptyList())
            }

            Result.success(response.packages.mapNotNull { item ->
                val packageName = item.packageName
                if (packageName.isBlank()) return@mapNotNull null
                // 同一次备份里 tag / 时间 / 备注都一样，Rust 侧只回传一份
                val description = item.description?.takeIf { it.isNotBlank() }
                RepoOverview(
                    packageName = packageName,
                    count = item.count,
                    time = item.time,
                    tags = item.tags,
                    description = description,
                    snapshots = item.snapshots.map { snapshot ->
                        BackupSnapshot(
                            id = snapshot.id,
                            label = snapshot.label,
                            tags = item.tags,
                            time = item.time,
                            size = snapshot.size,
                            description = description
                        )
                    },
                    hasApk = item.hasApk,
                    apkTags = item.apkTags?.takeIf { it.isNotBlank() },
                    apkTime = item.apkTime,
                    hasIcon = item.hasIcon
                )
            })
        } catch (t: Throwable) {
            AppLog.e(TAG, "repoOverview failed", t)
            Result.failure(t)
        }
    }

    override suspend fun listSnapshots(
        packageName: String?
    ): Result<List<BackupSnapshot>> = withContext(Dispatchers.IO) {
        try {
            // 目录的备份列表跨用户共享（和应用按包名共享是同一条规则），所以查仓库
            // 一律用归一后的身份，而不是当前用户空间里的绝对路径。应用 / 固定目标原样。
            val key = packageName?.let { MediaPath.normalize(it) }
            val request = ListSnapshotsRequest(packageName = key.orEmpty())
            val response = session.remote.call(retryOnDeath = true) {
                session.service().listSnapshots(request.toJsonText())
            }.parseJson<ListSnapshotsResponse>()

            if (!response.ok) {
                return@withContext Result.failure(
                    session.remote.error(response.error.orEmpty().ifBlank { "查询快照失败" })
                )
            }

            Result.success(response.snapshots.map { item ->
                BackupSnapshot(
                    id = item.id,
                    label = item.label,
                    tags = item.tags,
                    time = item.time,
                    size = item.size,
                    description = item.description?.takeIf { it.isNotBlank() }
                )
            })
        } catch (t: Throwable) {
            AppLog.e(TAG, "listSnapshots failed", t)
            Result.failure(t)
        }
    }

    override suspend fun loadEntries(
        packageName: String,
        appName: String
    ): Result<List<BackupEntry>> = runCatching {
        // 按包名查（rustic 侧按 label 前缀过滤），不再拉整个仓库的快照
        listSnapshots(packageName)
            .getOrThrow()
            .toEntries(appName)
    }

    override suspend fun backupApps(
        userId: Int,
        overview: List<RepoOverview>?
    ): List<AppInfo> {
        if (!repoPrefs.hasRepository()) return emptyList()

        // 调用方已经查过概况就直接用，避免列表页一次刷新查两遍仓库
        return (overview ?: repoOverview().getOrElse { emptyList() })
            .asSequence()
            .filter { it.hasApk }
            .map { item ->
                val tags = item.apkTags.orEmpty()
                AppInfo(
                    name = SnapshotMeta.appName(tags)?.ifBlank { item.packageName }
                        ?: item.packageName,
                    packageName = item.packageName,
                    version = SnapshotMeta.version(tags).orEmpty(),
                    versionCode = 0,
                    installTime = 0,
                    updateTime = 0,
                    userId = userId,
                    isSystem = false,
                    isFrozen = false,
                    onlyInBackup = true
                )
            }
            .toList()
    }

    override suspend fun backupOverview(
        appNames: Map<String, String>,
        overview: List<RepoOverview>?
    ): List<AppBackupSummary> = buildSummaries(
        appNames,
        overview ?: repoOverview().getOrElse { emptyList() }
    )

    override suspend fun backupOverviewResult(
        appNames: Map<String, String>
    ): Result<List<AppBackupSummary>> =
        repoOverview().map { buildSummaries(appNames, it) }

    /**
     * 取某个包备份里的图标（应用没装时用）。
     *
     * 内部完成：找 `<包名>:icon` 快照 → 导出到临时目录 → 读出字节 → 清理。
     * 失败就说明没有图标，调用方不用再判断。
     */
    override suspend fun loadIcon(packageName: String): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            val icon = listSnapshots(packageName)
                .getOrNull()
                ?.firstOrNull { it.label == "$packageName:${SnapshotKind.ICON.key}" }
                ?: return@withContext Result.failure(IllegalStateException("没有图标备份"))

            val dir = File(session.application.cacheDir, "icon_cache/${packageName.hashCode()}")
            dir.deleteRecursively()
            if (!dir.mkdirs()) {
                return@withContext Result.failure(IllegalStateException("无法创建缓存目录"))
            }

            try {
                val request = ExportSnapshotRequest(id = icon.id, dest = dir.absolutePath)
                val error = session.remote.call {
                    session.service().exportSnapshot(request.toJsonText())
                }
                if (!error.isNullOrBlank()) {
                    return@withContext Result.failure(session.remote.error(error))
                }

                val file = dir.listFiles()?.firstOrNull { it.isFile }
                    ?: return@withContext Result.failure(IllegalStateException("图标文件不存在"))

                Result.success(file.readBytes())
            } catch (t: Throwable) {
                Log.w(TAG, "loadIcon failed: $packageName", t)
                Result.failure(t)
            } finally {
                dir.deleteRecursively()
            }
        }

    override suspend fun systemDataCounts(): Map<SnapshotKind, Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                val counts = session.binder().systemDataCounts()
                    .parseJson<SystemDataCountsResponse>().counts

                SystemDataExporter.SYSTEM_KINDS.mapNotNull { kind ->
                    counts[kind.key]?.takeIf { it >= 0 }?.let { kind to it }
                }.toMap()
            }.getOrDefault(emptyMap())
        }

    // ==================== 内部 ====================

    private fun buildSummaries(
        appNames: Map<String, String>,
        overview: List<RepoOverview>
    ): List<AppBackupSummary> = overview.map { item ->
        val version = SnapshotMeta.version(item.tags)
        AppBackupSummary(
            packageName = item.packageName,
            count = item.count,
            latest = BackupEntry(
                title = appNames[item.packageName] ?: item.packageName,
                time = item.time,
                description = item.description,
                version = version,
                userName = SnapshotMeta.userName(item.tags),
                snapshots = item.snapshots
            ),
            hasIcon = item.hasIcon
        )
    }.sortedByDescending { it.latest.time }

    /** 快照 -> 备份行：去掉 icon，同一次备份（时间戳相同）的合成一行。 */
    private fun List<BackupSnapshot>.toEntries(appName: String): List<BackupEntry> =
        asSequence()
            .filterNot { it.kind() == SnapshotKind.ICON }
            .groupBy { SnapshotMeta.timestamp(it.tags) ?: it.time }
            .map { (time, group) ->
                val version = group.firstNotNullOfOrNull { SnapshotMeta.version(it.tags) }
                BackupEntry(
                    title = appName,
                    time = time,
                    description = group.firstNotNullOfOrNull { it.description },
                    version = version,
                    userName = group.firstNotNullOfOrNull { SnapshotMeta.userName(it.tags) },
                    snapshots = group
                )
            }
            .sortedByDescending { it.time }
            .toList()
}
