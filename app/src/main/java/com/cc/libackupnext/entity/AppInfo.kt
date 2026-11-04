package com.cc.libackupnext.entity

/**
 * 应用列表的纯元数据。
 *
 * 图标不再随列表一起传输：图标体积大、需要解码压缩，会拖慢列表首屏，
 * 应用数量多时还会撑爆 root 服务进程。图标改由 [com.cc.libackupnext.ui.util.AppIconStore]
 * 按需异步加载。
 */
data class AppInfo(

    val name: String,
    val packageName: String,
    val version: String,
    val versionCode: Long,
    val installTime: Long,
    val updateTime: Long,
    val userId: Int,
    val isSystem: Boolean,
    /** 是否已被冻结（ApplicationInfo.enabled == false）。 */
    val isFrozen: Boolean,

    /** 只在 rustic 备份里存在、当前用户并没有安装。 */
    val onlyInBackup: Boolean = false

)

/** 文件备份的伪应用：packageName 就是目录的绝对路径。 */
val AppInfo.isDirectory: Boolean get() = packageName.startsWith('/')

/**
 * 备份里的版本和当前装的版本是否不一致（备份过之后应用又更新过）。
 * 任一侧拿不到版本都算「比不出来」，返回 fals
 *
 * @param backupVersion 某条备份传 `BackupEntry.version`，概况传 `AppBackupSummary.latest.version`
 * @param installed 目标当前是否存在；默认按 `onlyInBackup` 推，详情页有更准的页面状态时显式传
 */
fun AppInfo.isVersionChangedFrom(
    backupVersion: String?,
    installed: Boolean = !onlyInBackup
): Boolean = installed &&
        version.isNotBlank() &&
        !backupVersion.isNullOrBlank() &&
        backupVersion != version

/** 把一个目录包装成 [AppInfo]，好复用应用详情页 / 批量页。 */
fun dirAppInfo(path: String): AppInfo {
    val normalized = path.trimEnd('/').ifBlank { path }
    return AppInfo(
        name = normalized.substringAfterLast('/').ifBlank { normalized },
        packageName = normalized,
        version = "",
        versionCode = 0,
        installTime = 0,
        updateTime = 0,
        userId = 0,
        isSystem = false,
        isFrozen = false
    )
}

/** 文件页默认列出的目录。 */
val DefaultBackupDirs = listOf(
    "/storage/emulated/0/DCIM",
    "/storage/emulated/0/Download",
    "/storage/emulated/0/Music",
    "/storage/emulated/0/Pictures"
)
