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
