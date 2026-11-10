package com.cc.libackupnext.data

/**
 * 本地文件夹选择器第一屏的一个入口：内部存储 / 根目录 / SD卡 / 外置存储。
 *
 * 只放展示用的纯数据。卷的枚举、分类、排序、以及 allowRoot 时插入「根目录」
 * 都由业务层完成，UI 只按列表渲染，不自己拼路径或判断卷类型。
 *
 * @param path 点击后要进入的绝对目录；allowRoot 的「根目录」就是 "/"。
 * @param label 列表上显示的中文名。
 * @param readOnly 卷是否以只读方式挂载，只用于副标题提示。
 */
data class LocalVolume(
    val path: String,
    val label: String,
    val readOnly: Boolean = false
)

/**
 * 本地路径栏的一段面包屑。
 *
 * [label] 是显示名（卷根段会换成卷名：内部存储 / 系统给出的卷描述），
 * [path] 是点击后要跳转的绝对目录；每段都直接跳到自己的目录，
 * 回「选择存储位置」页由列表里的「..」负责。
 */
data class PathSegment(
    val label: String,
    val path: String
)
