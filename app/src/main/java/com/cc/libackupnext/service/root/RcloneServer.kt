package com.cc.libackupnext.service.root

import android.content.Context
import android.os.IBinder
import com.cc.libackupnext.data.MediaPath
import com.cc.libackupnext.data.json.ListDirsRequest
import com.cc.libackupnext.data.json.OkResponse
import com.cc.libackupnext.service.IRcloneService
import com.cc.libackupnext.service.json.parseJson
import com.cc.libackupnext.util.AppLog
import java.io.File
import java.nio.ByteBuffer

/**
 * root 侧的 rclone 服务：目录浏览 / 建目录，以及远程 restic 服务的启停。
 *
 * 和 [RusticServer] 同在 :root 进程，但各自独立绑定、各加载各的 JNI 库
 * （librclone_bridge.so 与 librustic_bridge.so）。rustic 那边只管快照与仓库，
 * 不再碰 librclone.so 的路径或 inline 配置。
 *
 * 帧协议和 rustic 同一套，只是通道名不同 —— 传 [RCLONE_CHANNEL]，[runQueryNative]
 * 自己映射，这里不用管缓冲区。
 */
object RcloneServer {

    private const val TAG = "RcloneServer"

    private external fun nativeListDirs(buffer: ByteBuffer): Int
    private external fun nativeEnsureServe(buffer: ByteBuffer): Int
    private external fun nativeStopServe(buffer: ByteBuffer): Int
    private external fun nativeTransferStats(buffer: ByteBuffer): Int

    /** 告诉 Rust panic hook 往哪个目录写 rust-panic.txt。 */
    private external fun nativeSetLogDir(path: String): Int

    class Service : RootBinderService() {
        override fun createBinder(context: Context): IBinder = Impl(context).apply { initialize() }
    }

    private class Impl(private val context: Context) : IRcloneService.Stub() {

        private var nativeError: String? = null

        fun initialize() {
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

        override fun listDirs(request: String): String {
            val response = runQueryNative(
                request = request,
                native = ::nativeListDirs,
                nativeError = nativeError,
                failText = "rclone 未返回响应",
                channel = RCLONE_CHANNEL,
            )
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
            runQueryNative(
                request,
                ::nativeEnsureServe,
                nativeError,
                "启动远程服务失败",
                RCLONE_CHANNEL
            )

        override fun stopServe(): String =
            runQueryNative("{}", ::nativeStopServe, nativeError, "停止远程服务失败", RCLONE_CHANNEL)

        override fun transferStats(): String =
            runQueryNative("{}", ::nativeTransferStats, nativeError, "读取传输统计失败", RCLONE_CHANNEL)
    }
}
