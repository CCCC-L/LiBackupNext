package com.cc.libackupnext.service.gateway

import com.cc.libackupnext.data.BackupEntry
import com.cc.libackupnext.data.BackupGroup
import com.cc.libackupnext.data.BackupRequest
import com.cc.libackupnext.data.BackupSnapshot
import com.cc.libackupnext.data.SnapshotKind
import com.cc.libackupnext.data.SnapshotOverride
import com.cc.libackupnext.data.AppInfo
import com.cc.libackupnext.data.BackupProgress

/**
 * 仓库的**写**访问。
 *
 * 全部是「跑一次 rustic 命令」的语义，**不包含业务编排**：
 * 暂停应用、清理过期备份、导出自身 APK、判断能不能恢复 —— 这些属于 usecase 层。
 *
 * 边界怎么划：一个方法对应一次 IPC，参数就是这次命令需要的东西。
 * 如果你发现某个方法里出现了「先查一次、再判断、再写」的流程，那它应该去 usecase。
 *
 * 之所以和 [RepoReader] 分开：写操作不幂等、不能盲目重试、失败要让用户知道，
 * 调用方必须显式选择这个面，而不是从一个大接口里随手拿到。
 */
interface RepoWriter {

    /**
     * 备份一个应用：把请求原样交给 root 侧的 rustic。
     *
     * @param request 要备份什么（包名、勾选的类型、是否带图标等），由 usecase 组装
     * @param overrides 覆盖备份时沿用旧快照的 label / tag / 备注，键是 rustic 的类型名
     * @param deleteIds 新快照写成功后再删掉的旧快照 id
     * @param batchId 批量备份的批次标识，同一批传同一个值（仓库只打开一次）
     * @param operationId 一次逻辑操作的标识，写进所有快照的 tag
     * @param iconAlreadyBackedUp 已知图标已在仓库里就传 true，省一次快照查询
     * @param parentIds 类型 -> 上一次同类快照 id，传给 rustic 当 parent
     * @param onProgress 进度回调，由 root 侧的 poller 驱动
     */
    suspend fun write(
        request: BackupRequest,
        overrides: Map<String, SnapshotOverride> = emptyMap(),
        deleteIds: List<String> = emptyList(),
        batchId: String? = null,
        operationId: Long = System.currentTimeMillis(),
        iconAlreadyBackedUp: Boolean? = null,
        parentIds: Map<SnapshotKind, String>? = null,
        onProgress: (BackupProgress) -> Unit = {}
    ): Result<Unit>

    /**
     * 恢复指定的一批快照（按 id）。
     *
     * @param snapshots 要恢复的快照，由 usecase 按勾选单位从备份行里筛出来
     */
    suspend fun restoreSnapshots(
        app: AppInfo,
        snapshots: List<BackupSnapshot>,
        onProgress: (BackupProgress) -> Unit = {}
    ): Result<Unit>

    /** 删除一批快照（按 id）。 */
    suspend fun deleteSnapshots(ids: List<String>): Result<Unit>

    /** 删除一行里勾选的快照。 */
    suspend fun deleteSelected(entry: BackupEntry, groups: Set<BackupGroup>): Result<Unit>

    /** 删除某个包下的所有快照（含图标快照），返回删除数量。 */
    suspend fun deleteAll(packageName: String): Result<Int>

    /** 修改一行里所有快照的备注；description 为 null 表示移除。 */
    suspend fun updateEntryDescription(entry: BackupEntry, description: String?): Result<Unit>

    /**
     * 整理仓库：先回收孤儿图标，再 prune 释放已删快照占用的空间。
     *
     * @return 清掉的孤儿图标数量
     */
    suspend fun prune(): Result<Int>

    /** 只算不删：估算整理仓库能回收多少字节。 */
    suspend fun estimatePrune(): Result<Long>

    /**
     * 把本程序自己的安装包复制到仓库根目录，方便从仓库里直接装回来。
     *
     * 只属于备份类操作的收尾；受用户设置控制，失败不影响主流程。
     */
    suspend fun exportSelfApk()
}
