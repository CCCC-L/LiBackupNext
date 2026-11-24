package com.cc.libackupnext.service.root

/**
 * 断开回调去重：kill 一次 root，三个服务各回调一次，只留第一条 WARN。
 * 窗口外重新计数，不会吞掉下一次真实故障；内部加锁，不依赖回调线程假设。
 */
internal class DisconnectGate(private val windowMs: Long) {

    private val lock = Any()
    private var lastAt = 0L
    private var count = 0

    /** @return 窗口内序号：1 记 WARN，其余降级。 */
    fun mark(now: Long): Int = synchronized(lock) {
        if (now - lastAt < windowMs) {
            count++
        } else {
            lastAt = now
            count = 1
        }
        count
    }
}
