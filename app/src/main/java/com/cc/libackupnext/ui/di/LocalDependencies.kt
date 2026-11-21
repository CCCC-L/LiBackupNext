package com.cc.libackupnext.ui.di

import androidx.compose.runtime.staticCompositionLocalOf
import com.cc.libackupnext.data.prefs.BackupPrefs
import com.cc.libackupnext.data.prefs.PendingBatchStore
import com.cc.libackupnext.data.prefs.RepoDisplay
import com.cc.libackupnext.data.prefs.ViewPrefs
import com.cc.libackupnext.service.gateway.DeviceGateway
import com.cc.libackupnext.service.gateway.DirectoryBrowser
import com.cc.libackupnext.service.gateway.RepoAdmin
import com.cc.libackupnext.service.gateway.RepoReader
import com.cc.libackupnext.service.gateway.RepoWriter
import com.cc.libackupnext.service.progress.OperationPlanner
import com.cc.libackupnext.service.target.BackupTargets
import com.cc.libackupnext.service.usecase.BackupUseCase
import com.cc.libackupnext.service.usecase.RestoreUseCase

/**
 * 把 service 层的能力按需下发给 Composable。
 *
 * 用 CompositionLocal 而不是在每层 Composable 上加参数：调用链有四五层
 * （AppRoot → 列表页 → 详情页 → 弹窗），一路透传只会把中间层变成管道。
 * 也不用全局 object —— 这里可以随时 provide 一个 fake 做 UI 测试。
 *
 * 五个分开而不是一个聚合对象：一个页面只声明它真正用到的那个，
 * 备份页拿不到 [RepoAdmin]，就不会顺手去改仓库配置。
 *
 * 用 [staticCompositionLocalOf]：这些实现整个应用生命周期内不变，
 * 不需要读追踪，省掉每次重组时的订阅开销。
 */
val LocalRepoReader = staticCompositionLocalOf<RepoReader> {
    error("RepoReader 未提供：请确认 AppRoot 里有 CompositionLocalProvider")
}

val LocalRepoWriter = staticCompositionLocalOf<RepoWriter> {
    error("RepoWriter 未提供：请确认 AppRoot 里有 CompositionLocalProvider")
}

val LocalRepoAdmin = staticCompositionLocalOf<RepoAdmin> {
    error("RepoAdmin 未提供：请确认 AppRoot 里有 CompositionLocalProvider")
}

val LocalOperationPlanner = staticCompositionLocalOf<OperationPlanner> {
    error("OperationPlanner 未提供：请确认 AppRoot 里有 CompositionLocalProvider")
}

val LocalDeviceGateway = staticCompositionLocalOf<DeviceGateway> {
    error("DeviceGateway 未提供：请确认 AppRoot 里有 CompositionLocalProvider")
}

val LocalDirectoryBrowser = staticCompositionLocalOf<DirectoryBrowser> {
    error("DirectoryBrowser 未提供：请确认 AppRoot 里有 CompositionLocalProvider")
}

/**
 * 备份编排。与 [LocalRepoWriter] 的区别：
 * writer 只发命令，这个负责「暂停应用 → 备份 → 清理 → 导出自身」的完整流程。
 * 页面调 backup 而不是 writer.write，就不会漏掉收尾步骤。
 */
val LocalBackupUseCase = staticCompositionLocalOf<BackupUseCase> {
    error("BackupUseCase 未提供：请确认 AppRoot 里有 CompositionLocalProvider")
}

/** 恢复编排：前置判断 + 停应用 + 写回。 */
val LocalRestoreUseCase = staticCompositionLocalOf<RestoreUseCase> {
    error("RestoreUseCase 未提供：请确认 AppRoot 里有 CompositionLocalProvider")
}

/**
 * 备份目标的增删查。
 *
 * 界面不直接改 [BackupPrefs]：移除 / 加回目标的语义在 service 层，
 * 这里只下发「能增删 + 能订阅」这一个窄接口（ISP）。
 */
val LocalBackupTargets = staticCompositionLocalOf<BackupTargets> {
    error("BackupTargets 未提供：请确认 AppRoot 里有 CompositionLocalProvider")
}

// ==================== 本地配置 ====================

/**
 * 仓库展示信息（不含密码）。
 *
 * 单独一个 Local 而不是复用 [RepoPrefs]：界面要显示「当前是哪个仓库」，
 * 但没有任何理由能读到密码 —— 类型上就拿不到，比靠自觉可靠。
 */
val LocalRepoDisplay = staticCompositionLocalOf<RepoDisplay> {
    error("RepoDisplay 未提供：请确认 AppRoot 里有 CompositionLocalProvider")
}

/** 界面偏好：排序 / 筛选 / 外观。 */
val LocalViewPrefs = staticCompositionLocalOf<ViewPrefs> {
    error("ViewPrefs 未提供：请确认 AppRoot 里有 CompositionLocalProvider")
}

/** 备份行为：保留数、备份自身、图标来源、目录列表。 */
val LocalBackupPrefs = staticCompositionLocalOf<BackupPrefs> {
    error("BackupPrefs 未提供：请确认 AppRoot 里有 CompositionLocalProvider")
}

/** 未完成批次缓存：列表页读写「上次没跑完的活」。 */
val LocalPendingBatchStore = staticCompositionLocalOf<PendingBatchStore> {
    error("PendingBatchStore 未提供：请确认 AppRoot 里有 CompositionLocalProvider")
}
