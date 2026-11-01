package com.cc.libackupnext.service

import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.SharedMemory
import android.util.Log
import com.cc.libackupnext.service.util.AppState
import com.cc.libackupnext.service.util.RusticRequestBuilder
import com.cc.libackupnext.service.util.fixupTree
import com.cc.libackupnext.service.util.installApk
import com.cc.libackupnext.service.util.mediaPath
import com.cc.libackupnext.service.util.packageUid
import com.topjohnwu.superuser.ipc.RootService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer

/** 备份进度事件，state: running / done / skipped / finished */
data class BackupProgress(
    val stage: String,
    val state: String,
    val files: Long = 0,
    val reason: String = ""
)

/**
 * root 侧的 rustic 服务：`RootService` + JNI 桥接 + 快照增删改查。
 *
 * 主进程用的业务入口在 [RusticClient]：那边负责绑定服务、拼请求、把结果整理成 UI 能渲染的模型。
 * 这里只管「把 JSON 丢进共享内存、调 native、把结果 JSON 拿回来」。
 *
 * 跑在 root 进程的两个好处：
 * 1. 直接读写 /data/user、/data/media 等路径，不需要额外权限；
 * 2. 和 AppManagerService 一样通过 AIDL 暴露，主进程只拿结果。
 *
 * 与 Rust 的交互走 SharedMemory：
 * ```
 * [0, 64KB)      请求 / 响应帧
 * [64KB, 128KB)  备份进度帧
 * ```
 * 帧格式是「大端 i32 长度 + UTF-8 JSON」。
 */

object RusticServer {

    private const val TAG = "RusticServer"

    /** 请求 / 响应区 512KB + 进度区 64KB：快照列表要一次塞进响应里。 */
    private const val BUFFER_SIZE = 576 * 1024
    private const val PROGRESS_OFFSET = 512 * 1024
    private const val PROGRESS_COUNTER_SIZE = 4
    private const val PROGRESS_SLOT_SIZE = 256
    private const val PROGRESS_SLOT_COUNT = (64 * 1024 - PROGRESS_COUNTER_SIZE) / PROGRESS_SLOT_SIZE
    private const val SHARED_MEMORY_NAME = "libackup-rustic"

    /** /data/media/0/Android/data 的 gid（ext_data_rw）。 */
    private const val EXT_DATA_RW_GID = 1078

    /** /data/media/0/Android/obb 的 gid（ext_obb_rw）。*/
    private const val EXT_OBB_RW_GID = 1079

    /** 外部存储（/data/media）的 uid。外部存储的目录属于它，不是当前应用 uid。 */
    private const val MEDIA_RW_UID = 1023

    /**
     * 初始化 / 校验 rustic 仓库。
     *
     * @return 0 成功；1 业务失败（原因写回缓冲区）；2 共享内存不可用。
     */
    private external fun nativeInitRepo(buffer: ByteBuffer): Int

    /**
     * 备份，进度持续写进缓冲区的进度区。
     *
     * @return 0 成功；1 业务失败（原因写回缓冲区）；2 共享内存不可用。
     */
    private external fun nativeBackup(buffer: ByteBuffer): Int

    /**
     * 按 tag 查询快照，结果写回缓冲区。
     *
     * @return 0 成功；1 业务失败（原因写回缓冲区）；2 共享内存不可用。
     */
    private external fun nativeListSnapshots(buffer: ByteBuffer): Int

    /** 每个包的备份概况，响应只带最近一次备份的快照。 */
    private external fun nativeOverview(buffer: ByteBuffer): Int

    /** 清理孤儿图标快照。 */
    private external fun nativeCleanOrphanIcons(buffer: ByteBuffer): Int

    /**
     * 修改一批快照的备注。
     *
     * @return 0 成功；1 业务失败（原因写回缓冲区）；2 共享内存不可用。
     */
    private external fun nativeUpdateDescription(buffer: ByteBuffer): Int

    /** 删除一批快照。 */
    private external fun nativeDeleteSnapshots(buffer: ByteBuffer): Int

    /** 把快照恢复到指定目录。 */
    private external fun nativeRestore(buffer: ByteBuffer): Int

    /** 回收仓库空间（prune）。 */
    private external fun nativePrune(buffer: ByteBuffer): Int

    /** 只算不删：估算整理仓库能回收多少空间。 */
    private external fun nativePruneEstimate(buffer: ByteBuffer): Int

    /** 设置 rustic 的本地缓存目录（root 服务启动时调一次），空串表示关闭缓存。 */
    private external fun nativeSetCacheDir(path: String): Int

    class Service : RootService() {
        override fun onBind(intent: Intent): IBinder = Impl(applicationContext).apply { onBind() }
    }

    private class Impl(private val context: Context) : IRusticService.Stub() {

        private val appState = AppState(context)
        private val rusticRequest = RusticRequestBuilder(context, appState)

        private var nativeError: String? = null

        fun onBind() {
            nativeError = runCatching { loadNativeLibrary() }
                .exceptionOrNull()
                ?.let { t ->
                    Log.e(TAG, "load librustic_bridge.so failed", t)
                    "无法加载 rustic 本地库：${t.message ?: t.javaClass.simpleName}"
                }

            // 给 rustic 一个本地缓存目录，索引/tree 不用每次重读
            if (nativeError == null) {
                runCatching {
                    val cacheDir = File(context.cacheDir, "rustic")
                    cacheDir.mkdirs()
                    nativeSetCacheDir(cacheDir.absolutePath)
                }.onFailure { Log.w(TAG, "set rustic cache dir failed", it) }
            }
        }

        override fun initRepo(path: String, password: String): String {
            nativeError?.let { return it }

            return try {
                val shared = SharedMemory.create(SHARED_MEMORY_NAME, BUFFER_SIZE)
                try {
                    val buffer = shared.mapReadWrite()

                    val request = JSONObject().apply {
                        put("path", path)
                        put("password", password)
                    }
                    writeFrameAt(buffer, 0, request.toString())

                    val code = nativeInitRepo(buffer)

                    val response = runCatching { readFrameAt(buffer, 0) }.getOrNull()
                    if (code == 0 && (response == null || response.optBoolean("ok", true))) {
                        return ""
                    }

                    response?.optString("error").orEmpty()
                        .ifBlank { "初始化失败（code=$code）" }
                } finally {
                    shared.close()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "initRepo failed, path=$path", t)
                "初始化异常：${t.message ?: t.javaClass.simpleName}"
            }
        }

        override fun listSnapshots(request: String): String =
            runQuery(request, ::nativeListSnapshots)

        override fun overview(request: String): String =
            runQuery(request, ::nativeOverview, "读取备份概况失败")

        override fun cleanOrphanIcons(request: String): String =
            runQuery(request, ::nativeCleanOrphanIcons, "清理孤立图标失败")

        override fun updateDescription(request: String): String =
            runSimple(request, ::nativeUpdateDescription, "修改备注失败")

        override fun prune(request: String): String =
            runSimple(request, ::nativePrune, "回收空间失败")

        override fun pruneEstimate(request: String): String =
            runQuery(request, ::nativePruneEstimate, "估算可回收空间失败")

        override fun exportSnapshot(request: String): String {
            val options = JSONObject(request)
            val payload = JSONObject().apply {
                put("path", options.getString("path"))
                put("password", options.getString("password"))
                put(
                    "items",
                    JSONArray().put(
                        JSONObject()
                            .put("id", options.getString("id"))
                            .put("dest", options.getString("dest"))
                            .put("delete", false)
                    )
                )
            }
            return runSimple(payload.toString(), ::nativeRestore)
        }

        override fun deleteSnapshots(request: String): String =
            runSimple(request, ::nativeDeleteSnapshots)

        override fun restoreSnapshots(request: String, callback: IBackupProgressCallback?): String {
            nativeError?.let { return it }

            fun emit(stage: String, state: String) {
                val json = JSONObject().put("stage", stage).put("state", state)
                runCatching { callback?.onProgress(json.toString()) }
            }

            return try {
                val options = JSONObject(request)
                val packageName = options.getString("packageName")
                val userId = options.optInt("userId", 0)
                val repoPath = options.getString("repoPath")
                val repoPassword = options.getString("repoPassword")
                val deleteFirst = options.optBoolean("delete", true)
                val items = options.optJSONArray("items") ?: JSONArray()

                if (items.length() == 0) return "没有可恢复的内容"

                val byKind = mutableMapOf<String, String>()
                for (index in 0 until items.length()) {
                    val item = items.getJSONObject(index)
                    byKind[item.getString("kind")] = item.getString("id")
                }

                // 目录恢复：把快照写回原路径的父目录，顶层目录名就是文件夹名。
                byKind["dir"]?.let { id ->
                    emit("dir", "running")

                    // 和备份对称：写到 /data/media/0，而不是 FUSE 的 /sdcard。
                    // 用户目录归 media_rw、权限 2770 —— 和系统自己建的一致。
                    val error = restoreTree(
                        repoPath, repoPassword,
                        snapshotId = id,
                        target = File(mediaPath(packageName)),
                        delete = false,
                        uid = MEDIA_RW_UID,
                        gid = MEDIA_RW_UID,
                        parentContext = true,
                        dirMode = "2770"
                    )
                    if (error != null) {
                        emit("dir", "failed")
                        return error
                    }

                    emit("dir", "done")
                }

                byKind["apk"]?.let { id ->
                    emit("apk", "running")

                    val dir = File(context.cacheDir, "restore_apk").apply {
                        deleteRecursively()
                        mkdirs()
                    }

                    val error = restoreTo(repoPath, repoPassword, id, dir.absolutePath, delete = false)
                    if (error.isNotBlank()) {
                        emit("apk", "failed")
                        return error
                    }

                    val apk = dir.listFiles()?.firstOrNull { it.name.endsWith(".apk") }
                    if (apk == null) {
                        dir.deleteRecursively()
                        emit("apk", "failed")
                        return "恢复出来的 APK 不存在"
                    }

                    val installedUid = try {
                        installApk(apk, userId)
                    } finally {
                        dir.deleteRecursively()
                    }

                    if (installedUid.isFailure) {
                        emit("apk", "failed")
                        return installedUid.exceptionOrNull()?.message ?: "安装失败"
                    }

                    emit("apk", "done")
                }

                val appUid: Int? = packageUid(context, packageName, userId);
                for (kind in listOf("obb", "user", "user_de", "data")) {
                    val id = byKind[kind] ?: continue

                    if (appUid == null) {
                        emit(kind, "failed")
                        return "应用未安装，无法恢复数据"
                    }

                    val dest = when (kind) {
                        "obb" -> "/data/media/$userId/Android/obb"
                        "user" -> "/data/user/$userId"
                        "user_de" -> "/data/user_de/$userId"
                        else -> "/data/media/$userId/Android/data"
                    }

                    emit(kind, "running")
                    val error = restoreTree(
                        repoPath, repoPassword,
                        snapshotId = id,
                        target = File(dest, packageName),
                        delete = deleteFirst,
                        uid = appUid,
                        gid = when (kind) {
                            "data" -> EXT_DATA_RW_GID
                            "obb" -> EXT_OBB_RW_GID
                            else -> appUid
                        },
                        dirMode = when (kind) {
                            "obb", "data" -> "2770"
                            else -> null
                        }
                    )
                    if (error != null) {
                        emit(kind, "failed")
                        return error
                    }

                    emit(kind, "done")
                }

                // 权限 / appops / 电池白名单：解出 permissions.json，全量写回
                byKind["permissions"]?.let { id ->
                    emit("permissions", "running")

                    val uid = appUid ?: packageUid(context, packageName, userId)
                    if (uid == null) {
                        emit("permissions", "skipped")
                        return@let
                    }

                    val dir = File(context.cacheDir, "restore_permissions").apply {
                        deleteRecursively()
                        mkdirs()
                    }
                    val error =
                        restoreTo(repoPath, repoPassword, id, dir.absolutePath, delete = false)
                    val json = if (error.isBlank()) {
                        runCatching {
                            JSONObject(File(dir, "permissions.json").readText())
                        }.getOrNull()
                    } else {
                        null
                    }
                    dir.deleteRecursively()

                    if (json == null) {
                        if (error.isNotBlank()) {
                            emit("permissions", "failed")
                            return error
                        }
                        emit("permissions", "skipped")
                        return@let
                    }

                    val failed = appState.apply(json, packageName, uid)
                    if (failed > 0) {
                        Log.w(TAG, "恢复权限有 $failed 项失败：$packageName")
                    }
                    emit("permissions", "done")
                }

                ""
            } catch (t: Throwable) {
                Log.e(TAG, "restoreSnapshots failed", t)
                "恢复异常：${t.message ?: t.javaClass.simpleName}"
            }
        }

        /**
         * 把一个快照展开到 [target] 的父目录，再修正 [target] 的归属 / SELinux 上下文。
         *
         * @param delete 恢复后删掉父目录里快照没有的文件。**父目录里有别的东西时绝不能开**
         * @param parentContext 用父目录的 SELinux 上下文（外部存储必须 true）
         * @param dirMode 恢复后统一设的目录权限；null = 保持快照里记录的权限
         * @return 出错时返回给用户看的说明，成功返回 null
         */
        private fun restoreTree(
            repoPath: String,
            repoPassword: String,
            snapshotId: String,
            target: File,
            delete: Boolean,
            uid: Int,
            gid: Int,
            parentContext: Boolean = false,
            dirMode: String? = null
        ): String? {
            val dest = target.parentFile ?: return "路径不合法：${target.absolutePath}"

            val error = restoreTo(repoPath, repoPassword, snapshotId, dest.absolutePath, delete)
            if (error.isNotBlank()) return error

            // 备份文件夹为空时Rustic不会写入快照中，所以补建一下，如果补建失败直接返回
            if (!target.exists() && !target.mkdirs()) return null

            fixupTree(target, uid, gid, parentContext, dirMode)
            return null
        }

        /** 把单个快照恢复到 dest。 */
        private fun restoreTo(
            repoPath: String,
            repoPassword: String,
            id: String,
            dest: String,
            delete: Boolean
        ): String {
            val payload = JSONObject().apply {
                put("path", repoPath)
                put("password", repoPassword)
                put(
                    "items",
                    JSONArray().put(
                        JSONObject().put("id", id).put("dest", dest).put("delete", delete)
                    )
                )
            }
            return runSimple(payload.toString(), ::nativeRestore)
        }

        /**
         * 安装 APK 到指定用户。
         *
         * 直接用 pm install（--user 只影响目标用户），装完再确认一遍。
         */


        /**
         * 简单请求：把 JSON 写进共享内存，调 native，返回空串或错误。
         *
         * @param failText 失败时的文案前缀，例如「修改备注失败」
         */
        private fun runSimple(
            request: String,
            native: (ByteBuffer) -> Int,
            failText: String = "操作失败"
        ): String {
            nativeError?.let { return it }

            return try {
                val shared = SharedMemory.create(SHARED_MEMORY_NAME, BUFFER_SIZE)
                try {
                    val buffer = shared.mapReadWrite()
                    writeFrameAt(buffer, 0, request)

                    val code = native(buffer)
                    val response = runCatching { readFrameAt(buffer, 0) }.getOrNull()

                    if (code == 0 && (response == null || response.optBoolean("ok", true))) {
                        ""
                    } else {
                        response?.optString("error").orEmpty().ifBlank { "$failText（code=$code）" }
                    }
                } finally {
                    shared.close()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "${failText}异常", t)
                "${failText}异常：${t.message ?: t.javaClass.simpleName}"
            }
        }

        /**
         * 查询请求：把 JSON 写进共享内存，调 native，把结果 JSON 原样返回给主进程解析。
         *
         * @param failText 失败时的文案前缀，例如「查询失败」
         */
        private fun runQuery(
            request: String,
            native: (ByteBuffer) -> Int,
            failText: String = "查询失败"
        ): String {
            nativeError?.let { error ->
                return JSONObject().put("ok", false).put("error", error).toString()
            }

            return try {
                val shared = SharedMemory.create(SHARED_MEMORY_NAME, BUFFER_SIZE)
                try {
                    val buffer = shared.mapReadWrite()
                    writeFrameAt(buffer, 0, request)

                    val code = native(buffer)
                    val response = runCatching { readFrameAt(buffer, 0) }.getOrNull()

                    response?.toString() ?: JSONObject()
                        .put("ok", false)
                        .put("error", "$failText（code=$code）")
                        .toString()
                } finally {
                    shared.close()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "${failText}异常", t)
                JSONObject()
                    .put("ok", false)
                    .put("error", "${failText}异常：${t.message ?: t.javaClass.simpleName}")
                    .toString()
            }
        }

        override fun backup(request: String, callback: IBackupProgressCallback?): String {
            nativeError?.let { return it }

            return try {
                val rustRequest = rusticRequest.build(JSONObject(request))
                    ?: return "没有需要备份的内容"

                val shared = SharedMemory.create(SHARED_MEMORY_NAME, BUFFER_SIZE)
                try {
                    val buffer = shared.mapReadWrite()
                    writeFrameAt(buffer, 0, rustRequest)

                    val poller = ProgressPoller(buffer, callback)
                    poller.start()
                    try {
                        val code = nativeBackup(buffer)

                        val response = runCatching { readFrameAt(buffer, 0) }.getOrNull()
                        if (code == 0 && (response == null || response.optBoolean("ok", true))) {
                            ""
                        } else {
                            response?.optString("error").orEmpty()
                                .ifBlank { "备份失败（code=$code）" }
                        }
                    } finally {
                        poller.stop()
                    }
                } finally {
                    shared.close()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "backup failed", t)
                "备份异常：${t.message ?: t.javaClass.simpleName}"
            }
        }

        /** 把请求翻译成 Rust 侧要的备份项列表。 */
        /**
         * 外部存储的显示路径 → 真实路径。
         *
         * `/sdcard` 是 FUSE 挂载，root 读写会被权限和 SELinux 限制；`/data/media/0`
         * 是同一块存储的真实路径。界面上仍然显示 `/sdcard/...`，用户看得懂。
         *
         * 文件夹备份只做**主用户**，所以这里固定用 0。
         */
    }


    /** 定时把共享内存进度区里的事件按顺序转发给客户端。 */
    private class ProgressPoller(
        private val buffer: ByteBuffer,
        private val callback: IBackupProgressCallback?
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

                runCatching { callback?.onProgress(json.toString()) }
            }
        }

        fun start() = thread.start()

        fun stop() {
            running = false
            thread.interrupt()
            runCatching { thread.join(500) }
        }
    }

    /** root 进程里 java.library.path 不可靠，直接用绝对路径加载。 */
    private fun loadNativeLibrary() {
        System.loadLibrary("rustic_bridge")
    }

    private fun writeFrameAt(buffer: ByteBuffer, offset: Int, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        require(offset + 4 + bytes.size <= buffer.capacity()) { "请求数据过长" }

        buffer.putInt(offset, bytes.size)   // ByteBuffer 默认大端，Rust 侧按大端解析
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

}
