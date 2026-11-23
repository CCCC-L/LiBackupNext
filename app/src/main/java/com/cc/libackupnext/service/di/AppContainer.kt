package com.cc.libackupnext.service.di

import android.app.Application
import com.cc.libackupnext.data.prefs.BackupPrefs
import com.cc.libackupnext.data.prefs.PendingBatchStore
import com.cc.libackupnext.data.prefs.RepoDisplay
import com.cc.libackupnext.data.prefs.RepoPrefs
import com.cc.libackupnext.data.prefs.SharedBackupPrefs
import com.cc.libackupnext.data.prefs.SharedPendingBatchStore
import com.cc.libackupnext.data.prefs.SharedRepoPrefs
import com.cc.libackupnext.data.prefs.SharedViewPrefs
import com.cc.libackupnext.data.prefs.ViewPrefs
import com.cc.libackupnext.service.rclone.RcloneClient
import com.cc.libackupnext.service.gateway.DeviceGateway
import com.cc.libackupnext.service.gateway.DirectoryBrowser
import com.cc.libackupnext.service.gateway.RepoAdmin
import com.cc.libackupnext.service.gateway.RepoReader
import com.cc.libackupnext.service.gateway.RepoWriter
import com.cc.libackupnext.service.gateway.RusticAdminGateway
import com.cc.libackupnext.service.gateway.RusticReaderGateway
import com.cc.libackupnext.service.gateway.RusticSession
import com.cc.libackupnext.service.gateway.RootDeviceGateway
import com.cc.libackupnext.service.gateway.RusticWriterGateway
import com.cc.libackupnext.service.media.AndroidMediaRescanner
import com.cc.libackupnext.service.media.MediaRescanner
import com.cc.libackupnext.service.progress.DefaultOperationPlanner
import com.cc.libackupnext.service.progress.OperationPlanner
import com.cc.libackupnext.service.root.RootMediaFileLister
import com.cc.libackupnext.service.usecase.BackupUseCase
import com.cc.libackupnext.service.usecase.DefaultBackupUseCase
import com.cc.libackupnext.service.usecase.DefaultRestoreUseCase
import com.cc.libackupnext.service.usecase.RestoreUseCase
import com.cc.libackupnext.service.target.BackupTargets
import com.cc.libackupnext.service.target.PrefsBackupTargets

/**
 * 依赖容器：应用内唯一的「从哪拿实现」的地方。
 *
 * 每个对外属性都声明成**接口类型**，调用方拿不到实现类，也就没法绕过接口
 * 去用实现里多出来的方法 —— 这是拆分后调用方不用跟着改的前提。
 *
 * 装配关系：
 * ```
 * RusticSession（连接 / 凭据 / 错误归一，三个网关共享一份）
 *   ├─→ RusticReaderGateway  : RepoReader
 *   ├─→ RusticWriterGateway  : RepoWriter + DeviceGateway
 *   └─→ RusticAdminGateway   : RepoAdmin
 *
 * DefaultBackupUseCase(reader, writer, backupPrefs, device)
 * DefaultRestoreUseCase(writer, planner, device, backupTargets)
 * ```
 * 三个网关共用同一个 [RusticSession]：它们共享 root 服务绑定与凭据注入状态，
 * 各持一份会让每次操作都重新绑一次服务。
 */
class AppContainer(private val app: Application) {

    /** 连接状态：绑定、凭据注入、错误归一化。三个网关共享。 */
    private val session: RusticSession by lazy { RusticSession(app, repoPrefs) }

    // ==================== 本地配置 ====================

    /** 仓库位置与凭据。**只有 service 层该拿到它**。 */
    val repoPrefs: RepoPrefs by lazy { SharedRepoPrefs(app) }

    /** 给界面看的仓库信息（不含密码）。 */
    val repoDisplay: RepoDisplay get() = repoPrefs

    /** 界面偏好：排序 / 筛选 / 外观。UI 页面用这个。 */
    val viewPrefs: ViewPrefs by lazy { SharedViewPrefs(app) }

    /** 备份行为与运行期状态。 */
    val backupPrefs: BackupPrefs by lazy { SharedBackupPrefs(app) }

    /** 未完成批次缓存。 */
    val pendingBatch: PendingBatchStore by lazy { SharedPendingBatchStore(app) }

    // ==================== 仓库访问 ====================

    /** 仓库只读访问。 */
    val reader: RepoReader by lazy { RusticReaderGateway(session, repoPrefs) }

    /** 写入网关实例：同时对外提供 [RepoWriter] 与 [DeviceGateway] 两个面。 */
    private val writerGateway: RusticWriterGateway by lazy {
        RusticWriterGateway(session, reader, repoPrefs, backupPrefs)
    }

    /** 仓库写入（备份 / 恢复 / 删除 / 整理）。 */
    val writer: RepoWriter get() = writerGateway

    /** 本机设备操作（不碰仓库）：Wi-Fi、应用管理、备份期间的进程暂停。 */
    val device: DeviceGateway by lazy { RootDeviceGateway(session) }

    /** 仓库生命周期（初始化 / 切换 / 编辑）。 */
    val admin: RepoAdmin by lazy { RusticAdminGateway(session, repoPrefs) }

    /** 操作进度模型（纯逻辑，无 IO，无状态）。 */
    val planner: OperationPlanner get() = DefaultOperationPlanner

    /** 备份编排：暂停应用 → 写仓库 → 清理过期 → 导出自身 APK。 */
    val backup: BackupUseCase by lazy {
        DefaultBackupUseCase(reader, writer, backupPrefs, device)
    }

    /** 恢复编排：前置判断 → 停应用 → 写回 → 重建媒体索引。 */
    val restore: RestoreUseCase by lazy {
        DefaultRestoreUseCase(writer, planner, device, backupTargets, mediaRescanner, backupPrefs)
    }

    /** 恢复完文件夹后把媒体文件重新登记进 MediaStore（相册才看得见）。 */
    private val mediaRescanner: MediaRescanner by lazy {
        AndroidMediaRescanner(app, RootMediaFileLister())
    }

    /** 备份目标的增删查：界面和恢复编排都用它，不直接改 prefs。 */
    val backupTargets: BackupTargets by lazy { PrefsBackupTargets(backupPrefs) }

    /** 目录浏览（本地 / 远程），给仓库配置的文件夹选择器用。 */
    val browser: DirectoryBrowser by lazy { RcloneClient }
}
