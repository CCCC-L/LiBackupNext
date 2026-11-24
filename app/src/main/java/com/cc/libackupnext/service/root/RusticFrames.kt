package com.cc.libackupnext.service.root

import java.nio.ByteBuffer

/**
 * 主进程与 `:root` 之间的共享内存帧协议（rustic / rclone 共用）。
 *
 * 两边各映射同一块匿名共享内存，谁也不用序列化整块 buffer，只读写自己那一段。
 * 帧格式是「大端 i32 长度 + UTF-8 JSON」，必须和 Rust 侧一致，改动要同步
 * `rustic_server` / `rclone_bridge` 的解析代码。
 *
 * 每个通道有自己的名字和容量，两块内存互不相干，同名不会互相干扰。
 */

/** 一块共享内存通道。 */
internal class NativeChannel(val name: String, val size: Int)

/** 请求 / 响应区 512KB + 进度区 64KB：快照列表要一次塞进响应里。 */
internal const val RUSTIC_BUFFER_SIZE = 576 * 1024

internal const val RUSTIC_SHARED_MEMORY_NAME = "libackup-rustic"

/** rustic 的通道。 */
internal val RUSTIC_CHANNEL = NativeChannel(RUSTIC_SHARED_MEMORY_NAME, RUSTIC_BUFFER_SIZE)

/**
 * rclone 的通道。
 *
 * 只用请求 / 响应区，进度区空着。容量和 rustic 一致 —— native 侧可能对缓冲区大小
 * 有假设，这里不图省那 64KB。
 */
internal val RCLONE_CHANNEL = NativeChannel("libackup-rclone", RUSTIC_BUFFER_SIZE)

// ==================== 进度区（只有 rustic 用） ====================
// 布局：4 字节计数器 + N 个 256 字节的槽位；Rust 侧每产生一条进度就写一格并自增。

internal const val PROGRESS_OFFSET = 512 * 1024
internal const val PROGRESS_COUNTER_SIZE = 4
internal const val PROGRESS_SLOT_SIZE = 256
internal const val PROGRESS_SLOT_COUNT =
    (64 * 1024 - PROGRESS_COUNTER_SIZE) / PROGRESS_SLOT_SIZE

/**
 * 把一段 JSON 写进 [buffer] 的 [offset] 处。
 *
 * 前 4 字节是大端长度，后面是 UTF-8 正文 —— ByteBuffer 默认大端，Rust 侧按大端解析。
 */
internal fun writeFrameAt(buffer: ByteBuffer, offset: Int, json: String) {
    val bytes = json.toByteArray(Charsets.UTF_8)
    require(offset + 4 + bytes.size <= buffer.capacity()) { "请求数据过长" }

    buffer.putInt(offset, bytes.size)
    val dup = buffer.duplicate()
    dup.position(offset + 4)
    dup.put(bytes)
}

/** 帧里是 UTF-8 JSON 文本；原样返回，由调用方按需要解析成 DTO。 */
internal fun readFrameAt(buffer: ByteBuffer, offset: Int): String {
    val length = buffer.getInt(offset)
    require(length in 0..(buffer.capacity() - offset - 4)) { "数据长度非法: $length" }

    val bytes = ByteArray(length)
    val dup = buffer.duplicate()
    dup.position(offset + 4)
    dup.get(bytes)
    return String(bytes, Charsets.UTF_8)
}
