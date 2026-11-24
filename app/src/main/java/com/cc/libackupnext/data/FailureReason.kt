package com.cc.libackupnext.data

/**
 * 失败原因 + 它给用户的那句话。
 *
 * [wire] 是跨进程的稳定标识：Rust 回传的是它的子集，[REPO_INCOMPLETE] 由主进程判定；
 * [text] 是全项目唯一的失败文案。这里只有数据 —— 归因在懂那个后端的那一层，
 * 排版与未知兜底在 `BackendMessage`。
 *
 * 不含「仓库不存在 / 权限不足 / 内容不存在」：前两个是流程分支，后者软件本来就看不到。
 */
enum class FailureReason(val wire: String, val text: String) {

    /** 结构完整但解不开 */
    PASSWORD("password", "密码有误，请确认密码"),

    /** config 和 keys 没有成对存在 */
    REPO_INCOMPLETE("repo_incomplete", "仓库目录有缺失，无法打开"),

    UNREACHABLE("unreachable", "无法连接到远程服务器"),

    /** HTTP 401 / 403 */
    AUTH("auth", "远程服务器拒绝访问，请检查账号或密码"),

    TIMEOUT("timeout", "连接超时，请检查网络"),

    NO_SPACE("no_space", "存储空间不足"),

    /** 数据校验失败 */
    CORRUPT_DATA("corrupt_data", "备份数据已损坏");

    /** 断连类：远程仓库遇到它们要停 serve、批量停批；其余原因跟网络无关。 */
    val isTransport: Boolean get() = this in TRANSPORT

    companion object {
        private val TRANSPORT = setOf(UNREACHABLE, AUTH, TIMEOUT)

        /** 认不出来返回 null，调用方拿原文兜底。 */
        fun of(wire: String?): FailureReason? =
            wire?.let { value -> entries.firstOrNull { it.wire == value } }
    }
}
