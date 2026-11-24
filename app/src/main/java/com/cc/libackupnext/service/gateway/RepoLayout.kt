package com.cc.libackupnext.service.gateway

/**
 * 仓库目录结构：决定建库、打开，还是提示缺件。
 *
 * 缺 config 或 keys 时硬 open 会报成「密码错」（本地 backend 列不存在的目录
 * 只记日志、返回空列表），用户会以为自己密码记错了。
 */
internal data class RepoLayout(
    val config: Boolean,
    val keys: Boolean,
    val data: Boolean,
    val index: Boolean,
    val snapshots: Boolean
) {
    val state: RepoLayoutState get() = when {
        // config + keys 是仓库身份，缺一个都打不开；data/index/snapshots 空着正常
        config && keys -> RepoLayoutState.COMPLETE
        // 一个都没有才是空仓库
        !config && !keys && !data && !index && !snapshots -> RepoLayoutState.EMPTY
        // 其余是缺件：建库会留孤儿数据，打开也打不开
        else -> RepoLayoutState.INCOMPLETE
    }
}

internal enum class RepoLayoutState {
    /** 什么都没有：可以安全建库 */
    EMPTY,

    /** config + keys 都在：直接打开 */
    COMPLETE,

    /** 缺件：只能提示用户 */
    INCOMPLETE
}
