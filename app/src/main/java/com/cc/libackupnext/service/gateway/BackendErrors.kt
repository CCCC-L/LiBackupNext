package com.cc.libackupnext.service.gateway

/**
 * 后端失败的统一判定。
 *
 * 无状态纯函数，不需要注入：它只回答「这次失败是不是远程断连」，
 * 不持有连接、不发请求。UI 与业务层共用同一份判据，
 * 避免各处自己写 `t.message == "..."` 而慢慢漂移。
 */
object BackendErrors {

    /**
     * 远程断连时给用户的唯一文案。
     *
     * 同时是 [isConnectionLost] 的判定标记：网关层把远程失败统一换成这条
     * message，上层只认它，不认底层各种网络异常。
     */
    const val REMOTE_INTERRUPTED_MESSAGE = "远程仓库暂时无法连接，请确认是否可以连接"

    /** 这次失败是不是远程仓库断连（而非本地 IO / 参数错误）。 */
    fun isConnectionLost(t: Throwable?): Boolean =
        t is IllegalStateException && t.message == REMOTE_INTERRUPTED_MESSAGE
}
