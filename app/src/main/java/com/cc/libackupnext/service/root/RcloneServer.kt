package com.cc.libackupnext.service.root

import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.SharedMemory
import com.cc.libackupnext.data.MediaPath
import com.cc.libackupnext.data.json.ListDirsRequest
import com.cc.libackupnext.data.json.OkResponse
import com.cc.libackupnext.service.json.parseJson
import com.cc.libackupnext.service.json.toJsonText
import com.cc.libackupnext.util.AppLog
import com.topjohnwu.superuser.ipc.RootService
import java.io.File
import java.nio.ByteBuffer
import com.cc.libackupnext.service.root.loadRcloneBridge
import com.cc.libackupnext.service.IRcloneService

/**
 * root 侧的 rclone 服务：目录浏览 / 建目录，以及远程 restic 服务的启停。
 *
 * 和 [RusticServer] 同在 :root 进程，但各自独立绑定、各加载各的 JNI 库
 * （librclone_bridge.so 与 librustic_bridge.so）。rustic 那边只管快照与仓库，
 * 不再碰 librclone.so 的路径或 inline 配置。
 *
 * 帧协议沿用 rustic 侧那套（576KB SharedMemory，frame 0 是「大端 i32 长度 +
 * UTF-8 JSON」）。每次 create 都是独立 ashmem 区域，同名不会互相干扰；这里自带
 * 一份读写实现，避免改动 rustic 侧的 ProgressPoller。
 */
object RcloneServer {

    private const val TAG = "RcloneServer"

    /** 与 RusticServer 一致：请求 / 响应区 512KB + 进度区 64KB。 */
    private const val BUFFER_SIZE = 576 * 1024
    private const val SHARED_MEMORY_NAME = "libackup-rclone"

    private external fun nativeListDirs(buffer: ByteBuffer): Int
    private external fun nativeEnsureServe(buffer: ByteBuffer): Int
    private external fun nativeStopServe(buffer: ByteBuffer): Int

    /** 告诉 Rust panic hook 往哪个目录写 rust-panic.txt。 */
    private external fun nativeSetLogDir(path: String): Int

    private fun writeFrameAt(buffer: ByteBuffer, offset: Int, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        require(offset + 4 + bytes.size <= buffer.capacity()) { "请求数据过长" }

        buffer.putInt(offset, bytes.size)
        val dup = buffer.duplicate()
        dup.position(offset + 4)
        dup.put(bytes)
    }

    /** 帧里是 UTF-8 JSON 文本；原样返回，由调用方按需要解析成 DTO。 */
    private fun readFrameAt(buffer: ByteBuffer, offset: Int): String {
        val length = buffer.getInt(offset)
        require(length in 0..(buffer.capacity() - offset - 4)) { "数据长度非法: $length" }

        val bytes = ByteArray(length)
        val dup = buffer.duplicate()
        dup.position(offset + 4)
        dup.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    /** 把请求写进 frame 0、调 native、把响应帧读回来。 */
    private fun runQuery(request: String, native: (ByteBuffer) -> Int): String = try {
        val shared = SharedMemory.create(SHARED_MEMORY_NAME, BUFFER_SIZE)
        try {
            val buffer = shared.mapReadWrite()
            writeFrameAt(buffer, 0, request)
            val code = native(buffer)
            runCatching { readFrameAt(buffer, 0) }.getOrNull()
                ?: OkResponse(ok = false, error = "rclone 未返回响应（code=$code）")
                    .toJsonText()
        } finally {
            shared.close()
        }
    } catch (t: Throwable) {
        AppLog.e(TAG, "rclone 调用异常", t)
        OkResponse(
            ok = false,
            error = "rclone 调用异常：${t.message ?: t.javaClass.simpleName}"
        ).toJsonText()
    }

    class Service : RootService() {
        // libsu 不会实例化 Application，:root 的日志只能在这里装（install 幂等）
        override fun onCreate() {
            AppLog.install(applicationContext)
        }

        override fun onBind(intent: Intent): IBinder =
            Impl(applicationContext).apply { onBind() }
    }

    private class Impl(private val context: Context) : IRcloneService.Stub() {

        private var nativeError: String? = null

        fun onBind() {
            nativeError = runCatching { loadRcloneBridge() }
                .exceptionOrNull()
                ?.let { t ->
                    AppLog.e(TAG, "load librclone_bridge.so failed", t)
                    "无法加载 rclone 本地库：${t.message ?: t.javaClass.simpleName}"
                }
            if (nativeError == null) {
                runCatching {
                    val logDir = File(context.filesDir, "logs")
                    logDir.mkdirs()
                    nativeSetLogDir(logDir.absolutePath)
                }.onFailure { AppLog.w(TAG, "set rclone log dir failed", it) }
            }
        }

        private fun errorJson(message: String): String =
            OkResponse(ok = false, error = message).toJsonText()

        private fun query(request: String, native: (ByteBuffer) -> Int): String {
            nativeError?.let { return errorJson(it) }
            return runQuery(request, native)
        }

        override fun listDirs(request: String): String {
            nativeError?.let { return errorJson(it) }
            val response = runQuery(request, ::nativeListDirs)
            val options = runCatching { request.parseJson<ListDirsRequest>() }.getOrNull()
                ?: return response
            val target = options.fixup?.takeIf { it.isNotBlank() } ?: return response
            val ok = runCatching { response.parseJson<OkResponse>().ok }.getOrDefault(false)
            if (ok) {
                // 只有本地外部存储的请求才带 fixup。归属和 ctx 都按真身路径算：FUSE 路径的
                // 父 ctx 是 fuse:s0，chcon 不生效，目录会留成带 MLS 类别（DocumentsUI 看不见）。
                val dir = File(MediaPath.physical(target))
                val (uid, gid) = externalStorageOwner(context, dir.absolutePath)
                fixupTree(dir, uid, gid, parentContext = true, dirMode = "2770")
            }
            return response
        }

        override fun ensureServe(request: String): String =
            query(request, ::nativeEnsureServe)

        override fun stopServe(): String = query("{}", ::nativeStopServe)
    }
}
