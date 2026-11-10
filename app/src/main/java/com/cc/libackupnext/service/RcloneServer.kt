package com.cc.libackupnext.service

import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.SharedMemory
import com.cc.libackupnext.service.util.fixupTree
import com.cc.libackupnext.util.AppLog
import com.topjohnwu.superuser.ipc.RootService
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer

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

    /** 外部存储（/data/media）的 uid，目录属于它，不是当前应用 uid。 */
    private const val MEDIA_RW_UID = 1023

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

    private fun readFrameAt(buffer: ByteBuffer, offset: Int): JSONObject {
        val length = buffer.getInt(offset)
        require(length in 0..(buffer.capacity() - offset - 4)) { "数据长度非法: $length" }

        val bytes = ByteArray(length)
        val dup = buffer.duplicate()
        dup.position(offset + 4)
        dup.get(bytes)
        return JSONObject(String(bytes, Charsets.UTF_8))
    }

    /** 把请求写进 frame 0、调 native、把响应帧读回来。 */
    private fun runQuery(request: String, native: (ByteBuffer) -> Int): JSONObject = try {
        val shared = SharedMemory.create(SHARED_MEMORY_NAME, BUFFER_SIZE)
        try {
            val buffer = shared.mapReadWrite()
            writeFrameAt(buffer, 0, request)
            val code = native(buffer)
            runCatching { readFrameAt(buffer, 0) }.getOrNull()
                ?: JSONObject().put("ok", false)
                    .put("error", "rclone 未返回响应（code=$code）")
        } finally {
            shared.close()
        }
    } catch (t: Throwable) {
        AppLog.e(TAG, "rclone 调用异常", t)
        JSONObject().put("ok", false)
            .put("error", "rclone 调用异常：${t.message ?: t.javaClass.simpleName}")
    }

    class Service : RootService() {
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
            JSONObject().put("ok", false).put("error", message).toString()

        private fun query(request: String, native: (ByteBuffer) -> Int): String {
            nativeError?.let { return errorJson(it) }
            return runQuery(request, native).toString()
        }

        override fun listDirs(request: String): String {
            nativeError?.let { return errorJson(it) }
            val response = runQuery(request, ::nativeListDirs)
            val options = runCatching { JSONObject(request) }.getOrNull()
                ?: return response.toString()
            val target = options.optString("fixup").takeIf { it.isNotBlank() }
                ?: return response.toString()
            if (response.optBoolean("ok", false)) {
                // rclone 在 uid 0 下建出来的目录：交还 media_rw，并刷成父目录的
                // SELinux 上下文。只有本地外部存储的请求才带 fixup，远程不受影响。
                fixupTree(
                    File(target), MEDIA_RW_UID, MEDIA_RW_UID,
                    parentContext = true, dirMode = "2770"
                )
            }
            return response.toString()
        }

        override fun ensureServe(request: String): String =
            query(request, ::nativeEnsureServe)

        override fun stopServe(): String = query("{}", ::nativeStopServe)
    }
}
