package com.cc.libackupnext.service.root

import com.cc.libackupnext.service.IBackupProgressCallback
import java.nio.ByteBuffer

/**
 * 把共享内存进度区里的事件按顺序转发给主进程。
 *
 * Rust 侧每产生一条进度就往环形缓冲区写一格并自增计数；这里定时读计数、
 * 把新增的格子按序回调出去。用轮询而不是事件通知，是因为跨进程没有更轻的信号机制，
 * 60ms 的间隔对进度条来说足够。
 *
 * 从 `RusticServer` 里抽出来：它和「备份还是恢复」无关，只认缓冲区。
 */
internal class RusticProgressPoller(
    private val buffer: ByteBuffer,
    private val callback: IBackupProgressCallback?,
) {

    @Volatile
    private var running = true

    private var lastEvent = 0

    private val thread = Thread({
        while (running) {
            drain()
            try {
                Thread.sleep(60)
            } catch (_: InterruptedException) {
                break
            }
        }
        // 退出前再读一次，避免最后几条事件丢掉
        drain()
    }, "rustic-progress").apply { isDaemon = true }

    private fun drain() {
        val counter = runCatching { buffer.getInt(PROGRESS_OFFSET) }.getOrDefault(lastEvent)
        while (lastEvent < counter) {
            val next = lastEvent + 1
            val slot = (next - 1) % PROGRESS_SLOT_COUNT
            val offset = PROGRESS_OFFSET + PROGRESS_COUNTER_SIZE + slot * PROGRESS_SLOT_SIZE

            val json = runCatching { readFrameAt(buffer, offset) }.getOrNull() ?: return
            lastEvent = next

            runCatching { callback?.onProgress(json) }
        }
    }

    fun start() = thread.start()

    fun stop() {
        running = false
        thread.interrupt()
        runCatching { thread.join(500) }
    }
}
