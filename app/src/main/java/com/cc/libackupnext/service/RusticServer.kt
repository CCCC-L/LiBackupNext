package com.cc.libackupnext.service

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.SharedMemory
import android.util.Log
import com.cc.libackupnext.data.SnapshotKind
import com.cc.libackupnext.data.json.BackupItemDto
import com.cc.libackupnext.data.json.BackupOptions
import com.cc.libackupnext.data.json.ExportSnapshotRequest
import com.cc.libackupnext.data.json.InitRepoRequest
import com.cc.libackupnext.data.json.OkResponse
import com.cc.libackupnext.data.json.OperationEnvelope
import com.cc.libackupnext.data.json.RestoreSnapshotsRequest
import com.cc.libackupnext.data.json.RestoreTreeItem
import com.cc.libackupnext.data.json.RestoreTreeRequest
import com.cc.libackupnext.data.json.RusticBackupRequest
import com.cc.libackupnext.data.json.SystemDataCountsResponse
import com.cc.libackupnext.service.json.AppJson
import com.cc.libackupnext.service.json.parseJson
import com.cc.libackupnext.service.json.toJsonText
import com.cc.libackupnext.service.util.AppState
import com.cc.libackupnext.service.util.RusticRequestBuilder
import com.cc.libackupnext.service.util.SystemDataExporter
import com.cc.libackupnext.service.util.SystemDataRestorer
import com.cc.libackupnext.service.util.fixupTree
import com.cc.libackupnext.service.util.installApks
import com.cc.libackupnext.service.util.mediaPath
import com.cc.libackupnext.service.util.packageUid
import com.cc.libackupnext.util.AppLog
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.nio.ByteBuffer

/**
 * 恢复时要落到「应用数据目录」的那几种快照。
 *
 * 顺序就是恢复顺序：obb 先于 user / user_de / data。
 */
private val APP_DATA_KINDS = listOf(
    SnapshotKind.OBB,
    SnapshotKind.USER,
    SnapshotKind.USER_DE,
    SnapshotKind.DATA
)

/** 数据类快照恢复到哪里、归谁、什么权限。 */
private data class RestoreSpec(val dir: String, val gid: Int, val dirMode: String?)

/** 备份进度事件，state: running / done / skipped / finished。字段名同时是进度帧协议。 */
@Serializable
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
     * 初始化 / 校验 rustic 仓库。请求帧里带候选 path / password，只用于本次校验，
     * 不写 Rust 侧的全局凭据。
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

    /** 告诉 Rust panic hook 往哪个目录写 rust-panic.txt（panic=abort 前留痕）。 */
    private external fun nativeSetLogDir(path: String): Int

    /**
     * 把仓库地址、密码和后端选项写进 Rust 侧的静态凭据，顺便清掉三个仓库缓存。
     *
     * 这是业务命令唯一的凭据来源：root 侧 Kotlin 不留副本，业务请求的 JSON
     * 都不带 path / password。initRepo 例外，它自带候选凭据只用于本次校验。
     * path 可以是本地路径或带协议前缀的仓库位置，options 是 JSON 对象字符串。
     */
    private external fun nativeSetRepo(path: String, password: String, options: String): Int

    class Service : RootService() {
        override fun onBind(intent: Intent): IBinder = Impl(applicationContext).apply { onBind() }
    }

    private class Impl(private val context: Context) : IRusticService.Stub() {

        private val appState = AppState(context)
        private val rusticRequest = RusticRequestBuilder(context, appState)

        private var nativeError: String? = null

        fun onBind() {
            AppLog.i(TAG, "root 服务绑定，加载 native 库")
            nativeError = runCatching { loadRusticBridge() }
                .exceptionOrNull()
                ?.let { t ->
                    AppLog.e(TAG, "load librustic_bridge.so failed", t)
                    "无法加载 rustic 本地库：${t.message ?: t.javaClass.simpleName}"
                }

            // 缓存目录 + Rust panic 的落盘目录，都在 root 服务启动时设一次
            if (nativeError == null) {
                runCatching {
                    val cacheDir = File(context.cacheDir, "rustic")
                    cacheDir.mkdirs()
                    nativeSetCacheDir(cacheDir.absolutePath)

                    val logDir = File(context.filesDir, "logs")
                    logDir.mkdirs()
                    nativeSetLogDir(logDir.absolutePath)
                    AppLog.i(TAG, "rustic 缓存目录与 panic 日志目录已注入")
                }.onFailure { AppLog.w(TAG, "set rustic cache/log dir failed", it) }
            }
        }

        /** 从请求 JSON 里取主进程透传的操作 id，让两侧日志能串成一条链。 */
        private fun opOf(request: String): String? = runCatching {
            request.parseJson<OperationEnvelope>().op.takeIf { it.isNotBlank() }
        }.getOrNull()

        /** 主进程在绑定 / 初始化成功 / 改密码 / 清配置时调一次，凭据只进 Rust 静态配置。 */
        override fun setRepo(path: String, password: String, options: String) {
            if (nativeError != null) return
            AppLog.i(TAG, "setRepo：注入仓库凭据（不含密码内容）")
            runCatching { nativeSetRepo(path, password, options) }
                .onFailure { AppLog.e(TAG, "nativeSetRepo failed", it) }
        }

        /**
         * 候选路径、密码和后端选项只随这一次请求下传，不写全局凭据。
         * 成功与否由主进程判断，失败时不需要任何回滚或状态恢复。
         */
        override fun initRepo(path: String, password: String, options: String): String {
            nativeError?.let { return it }
            val request = InitRepoRequest(
                path = path,
                password = password,
                // 和 nativeSetRepo 一样容个错：非法 JSON 退化成空 options，本地仓库照常工作
                options = runCatching { options.parseJson<Map<String, String>>() }
                    .getOrDefault(emptyMap())
            )
            return runSimple(request.toJsonText(), ::nativeInitRepo, "初始化失败")
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

        /**
         * 把一个快照解到指定目录，再把目录的归属交还给主进程。
         *
         * 和目录恢复走同一个 [restoreTree]，区别只是快照里只有一个文件、没有顶层目录名，
         * 所以解包目录本身就是目标。
         */
        override fun exportSnapshot(request: String): String {
            val options = request.parseJson<ExportSnapshotRequest>()
            val dest = File(options.dest)
            val uid = context.applicationInfo.uid

            return restoreTree(
                snapshotId = options.id,
                target = dest,
                uid = uid,
                gid = uid,
                dest = dest,
                parentContext = true
            ) ?: ""
        }

        override fun deleteSnapshots(request: String): String =
            runSimple(request, ::nativeDeleteSnapshots)

        override fun systemDataCounts(): String = try {
            // 三类都由 root 侧数：Wi-Fi 走 WifiManager，联系人 / 通话记录走 provider 的外部通道
            val counts = buildMap {
                for (kind in SystemDataExporter.SYSTEM_KINDS) {
                    SystemDataExporter.count(context, kind)?.let { put(kind.key, it) }
                }
            }
            SystemDataCountsResponse(ok = true, counts = counts).toJsonText()
        } catch (t: Throwable) {
            AppLog.e(TAG, "统计系统数据失败", t)
            OkResponse(ok = false, error = t.message ?: "统计失败").toJsonText()
        }

        @Suppress("DEPRECATION")
        override fun setWifiEnabled(enabled: Boolean): String = try {
            val manager = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return "拿不到 WifiManager"

            when {
                manager.isWifiEnabled == enabled -> ""
                manager.setWifiEnabled(enabled) -> ""
                else -> "系统拒绝了这次操作"
            }
        } catch (t: Throwable) {
            AppLog.e(TAG, "切换 Wi-Fi 失败", t)
            t.message ?: "切换 Wi-Fi 失败"
        }

        override fun restoreSnapshots(request: String, callback: IBackupProgressCallback?): String {
            nativeError?.let { return it }
            val op = opOf(request)
            val startedAt = System.currentTimeMillis()
            AppLog.i(TAG, "root restore 开始", op = op)

            fun emit(stage: String, state: String) {
                // 进度帧只有 256 字节：encodeDefaults=false 保证不写 files / reason
                val json = BackupProgress(stage = stage, state = state).toJsonText()
                runCatching { callback?.onProgress(json) }
            }

            fun emit(kind: SnapshotKind, state: String) = emit(kind.key, state)

            return try {
                val options = request.parseJson<RestoreSnapshotsRequest>()
                val packageName = options.packageName
                val userId = options.userId
                val deleteFirst = options.delete

                if (options.items.isEmpty()) return "没有可恢复的内容"

                // 类型 -> 快照 id
                val byKind = mutableMapOf<SnapshotKind, String>()
                for (item in options.items) {
                    val kind = SnapshotKind.of(item.kind) ?: continue
                    byKind[kind] = item.id
                }

                // 目录恢复：把快照写回原路径的父目录，顶层目录名就是文件夹名。
                byKind[SnapshotKind.DIR]?.let { id ->
                    emit(SnapshotKind.DIR, "running")

                    // 和备份对称：写到 /data/media/0，而不是 FUSE 的 /sdcard。
                    // 用户目录归 media_rw、权限 2770 —— 和系统自己建的一致。
                    val error = restoreTree(
                        snapshotId = id,
                        target = File(mediaPath(packageName)),
                        delete = false,
                        uid = MEDIA_RW_UID,
                        gid = MEDIA_RW_UID,
                        parentContext = true,
                        dirMode = "2770"
                    )
                    if (error != null) {
                        emit(SnapshotKind.DIR, "failed")
                        return error
                    }

                    emit(SnapshotKind.DIR, "done")
                }

                byKind[SnapshotKind.APK]?.let { id ->
                    emit(SnapshotKind.APK, "running")

                    val dir = File(context.cacheDir, "restore_apk").apply {
                        deleteRecursively()
                        mkdirs()
                    }

                    val error = restoreTo(id, dir.absolutePath, delete = false)
                    if (error.isNotBlank()) {
                        emit(SnapshotKind.APK, "failed")
                        return error
                    }

                    val apks = dir.listFiles()
                        ?.filter { it.isFile && it.name.endsWith(".apk") }
                        .orEmpty()
                    if (apks.isEmpty()) {
                        dir.deleteRecursively()
                        emit(SnapshotKind.APK, "failed")
                        return "恢复出来的 APK 不存在"
                    }

                    val installedUid = try {
                        installApks(apks, userId)
                    } finally {
                        dir.deleteRecursively()
                    }

                    if (installedUid.isFailure) {
                        emit(SnapshotKind.APK, "failed")
                        return installedUid.exceptionOrNull()?.message ?: "安装失败"
                    }

                    emit(SnapshotKind.APK, "done")
                }
                val appUid: Int? = packageUid(context, packageName, userId)

                // 恢复 权限 / appops / 电池白名单
                byKind[SnapshotKind.PERMISSIONS]?.let { id ->
                    emit(SnapshotKind.PERMISSIONS, "running")

                    val uid = appUid ?: packageUid(context, packageName, userId)
                    if (uid == null) {
                        emit(SnapshotKind.PERMISSIONS, "failed")
                        return "应用未安装，无法恢复数据"
                    }

                    val json = try {
                        loadJsonFromRepo(id, SnapshotKind.PERMISSIONS.key)
                    } catch (e: IllegalStateException) {
                        emit(SnapshotKind.PERMISSIONS, "failed")
                        return e.message.orEmpty()
                    } catch (e: IllegalArgumentException) {
                        emit(SnapshotKind.PERMISSIONS, "skipped")
                        return@let
                    }

                    val failed = appState.apply(json, packageName, uid)
                    if (failed > 0) {
                        // 部分失败不会让整个恢复报错，但必须留痕：这是典型的「功能异常」
                        AppLog.w(TAG, "恢复权限有 $failed 项失败：$packageName", op = opOf(request))
                    }
                    emit(SnapshotKind.PERMISSIONS, "done")
                }

                for (kind in APP_DATA_KINDS) {
                    val id = byKind[kind] ?: continue

                    if (appUid == null) {
                        emit(kind, "failed")
                        return "应用未安装，无法恢复数据"
                    }

                    // 落点 / 归属 / 权限：只有数据类快照会走到这里
                    val spec = when (kind) {
                        SnapshotKind.OBB -> RestoreSpec(
                            dir = "/data/media/$userId/Android/obb",
                            gid = EXT_OBB_RW_GID,
                            dirMode = "2770"
                        )

                        SnapshotKind.USER -> RestoreSpec("/data/user/$userId", appUid, null)
                        SnapshotKind.USER_DE -> RestoreSpec("/data/user_de/$userId", appUid, null)
                        SnapshotKind.DATA -> RestoreSpec(
                            dir = "/data/media/$userId/Android/data",
                            gid = EXT_DATA_RW_GID,
                            dirMode = "2770"
                        )

                        else -> return "不支持恢复的快照类型：${kind.key}"
                    }

                    emit(kind, "running")
                    val error = restoreTree(
                        snapshotId = id,
                        target = File(spec.dir, packageName),
                        delete = deleteFirst,
                        uid = appUid,
                        gid = spec.gid,
                        dirMode = spec.dirMode
                    )
                    if (error != null) {
                        emit(kind, "failed")
                        return error
                    }

                    emit(kind, "done")
                }

                // 系统数据（Wi-Fi / 联系人 / 通话记录）：Wi-Fi 走 WifiManager，
                // 另外两类走 provider 的外部通道，都不碰数据库文件
                val failures = mutableListOf<String>()
                for (kind in SystemDataExporter.SYSTEM_KINDS) {
                    val id = byKind[kind] ?: continue
                    emit(kind, "running")

                    val result = try {
                        // 解到临时目录（彩信的附件也在里面），用完删掉
                        val dir = loadSystemDirFromRepo(id, kind)
                        try {
                            SystemDataRestorer.restoreFrom(context, kind, dir)
                        } finally {
                            dir.deleteRecursively()
                        }
                    } catch (t: Throwable) {
                        AppLog.e(TAG, "恢复 ${kind.key} 失败", t, opOf(request))
                        emit(kind, "failed")
                        return t.message ?: "恢复失败"
                    }
                    Log.d(
                        TAG,
                        "恢复 ${kind.key}：新增 ${result.added}，跳过 ${result.skipped}，" +
                                "失败 ${result.failed}"
                    )
                    if (result.failed > 0) {
                        failures += "${kind.key} 有 ${result.failed} 条写入失败"
                    }
                    emit(kind, "done")
                }

                // 部分写入失败不当作整次恢复失败，返回失败的内容
                val outcome = failures.joinToString("；")
                AppLog.i(
                    TAG,
                    "root restore 完成 耗时=${System.currentTimeMillis() - startedAt}ms 失败项=${failures.size}",
                    op = op
                )
                outcome
            } catch (t: Throwable) {
                AppLog.e(TAG, "restoreSnapshots failed", t, op)
                "恢复异常：${t.message ?: t.javaClass.simpleName}"
            }
        }

        /** 从仓库里取回备份文件原文；坏文件抛 IllegalArgumentException 让调用方标成「跳过」。 */
        private fun loadJsonFromRepo(id: String, kind: String): String {
            val dir = File(context.cacheDir, "restore_$kind").apply {
                deleteRecursively()
                mkdirs()
            }
            try {
                val error = restoreTo(id, dir.absolutePath, delete = false)
                if (error.isNotBlank()) {
                    throw IllegalStateException(error)
                }

                // 快照里的文件名就是 <kind>.json（导出时就是这么写的）
                val text = try {
                    File(dir, "$kind.json").readText()
                } catch (t: Throwable) {
                    throw IllegalArgumentException()
                }
                // 先确认是个 JSON 对象，再交给 AppState / SystemDataRestorer 按类型解码
                if (runCatching { AppJson.parseToJsonElement(text).jsonObject }.isFailure) {
                    throw IllegalArgumentException()
                }
                return text
            } finally {
                dir.deleteRecursively()
            }
        }

        /**
         * 把系统数据快照解到临时目录，返回这个目录 —— **调用方负责删**。
         *
         * 和 [loadJsonFromRepo] 的区别：彩信的附件是快照里的独立文件，不能只把 JSON
         * 读成字符串。坏快照抛 IllegalArgumentException，让调用方标成「跳过」。
         */
        private fun loadSystemDirFromRepo(id: String, kind: SnapshotKind): File {
            val dir = File(context.cacheDir, "restore_${kind.key}").apply {
                deleteRecursively()
                mkdirs()
            }
            try {
                val error = restoreTo(id, dir.absolutePath, delete = false)
                if (error.isNotBlank()) throw IllegalStateException(error)

                // 快照里一定有一个 <kind>.json（导出时就是这么写的）
                val text = try {
                    File(dir, "${kind.key}.json").readText()
                } catch (t: Throwable) {
                    throw IllegalArgumentException()
                }
                // 先确认是个 JSON 对象，再交给各 handler 按类型解码
                if (runCatching { AppJson.parseToJsonElement(text).jsonObject }.isFailure) {
                    throw IllegalArgumentException()
                }
                return dir
            } catch (t: Throwable) {
                dir.deleteRecursively()
                throw t
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
        /**
         * 把一个快照解到 [dest]，再修正 [target] 的归属 / SELinux 上下文。
         *
         * 两种用法：
         * - 目录 / 应用数据恢复：快照里带着顶层目录名（备份时 as_path 就是文件夹名），
         *   解到 [target] 的父目录正好落在 [target]，所以 [dest] 默认取父目录；
         * - 导出图标这类单文件快照：解包目录本身就是目标，[dest] 和 [target] 传同一个路径。
         *
         * @param delete 恢复后删掉 [dest] 里快照没有的文件。**里面还有别的东西时绝不能开**
         * @param parentContext 用父目录的 SELinux 上下文（外部存储必须 true）
         * @param dirMode 恢复后统一设的目录权限；null = 保持快照里记录的权限
         * @return 出错时返回给用户看的说明，成功返回 null
         */
        private fun restoreTree(
            snapshotId: String,
            target: File,
            uid: Int,
            gid: Int,
            delete: Boolean = false,
            // target 都是 File(父目录, 名字) 拼出来的，父目录一定在
            dest: File = target.parentFile!!,
            parentContext: Boolean = false,
            dirMode: String? = null
        ): String? {
            val error = restoreTo(snapshotId, dest.absolutePath, delete)
            if (error.isNotBlank()) return error

            // 备份文件夹为空时Rustic不会写入快照中，所以补建一下，如果补建失败直接返回
            if (!target.exists() && !target.mkdirs()) return null

            fixupTree(target, uid, gid, parentContext, dirMode)
            return null
        }

        /** 把单个快照恢复到 dest。 */
        private fun restoreTo(id: String, dest: String, delete: Boolean): String {
            val payload = RestoreTreeRequest(
                items = listOf(RestoreTreeItem(id = id, dest = dest, delete = delete))
            )
            return runSimple(payload.toJsonText(), ::nativeRestore)
        }

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
            val op = opOf(request)
            val startedAt = System.currentTimeMillis()

            return try {
                val shared = SharedMemory.create(SHARED_MEMORY_NAME, BUFFER_SIZE)
                try {
                    val buffer = shared.mapReadWrite()
                    writeFrameAt(buffer, 0, request)

                    val code = native(buffer)
                    val raw = runCatching { readFrameAt(buffer, 0) }.getOrNull()
                    val response = raw?.let {
                        runCatching { it.parseJson<OkResponse>() }.getOrNull()
                    }
                    AppLog.i(
                        TAG,
                        "$failText native 返回 code=$code 耗时=${System.currentTimeMillis() - startedAt}ms",
                        op = op
                    )

                    // 和原来的 optBoolean("ok", true) 一致：拿不到 / 解析不了响应当成功
                    if (code == 0 && (response == null || response.ok)) {
                        ""
                    } else {
                        response?.error.orEmpty().ifBlank { "$failText（code=$code）" }
                    }
                } finally {
                    shared.close()
                }
            } catch (t: Throwable) {
                AppLog.e(TAG, "${failText}异常", t, op)
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
                return OkResponse(ok = false, error = error).toJsonText()
            }
            val op = opOf(request)
            val startedAt = System.currentTimeMillis()

            return try {
                val shared = SharedMemory.create(SHARED_MEMORY_NAME, BUFFER_SIZE)
                try {
                    val buffer = shared.mapReadWrite()
                    writeFrameAt(buffer, 0, request)

                    val code = native(buffer)
                    val response = runCatching { readFrameAt(buffer, 0) }.getOrNull()
                    AppLog.i(
                        TAG,
                        "$failText native 返回 code=$code 耗时=${System.currentTimeMillis() - startedAt}ms",
                        op = op
                    )

                    response ?: OkResponse(ok = false, error = "$failText（code=$code）")
                        .toJsonText()
                } finally {
                    shared.close()
                }
            } catch (t: Throwable) {
                AppLog.e(TAG, "${failText}异常", t, op)
                OkResponse(
                    ok = false,
                    error = "${failText}异常：${t.message ?: t.javaClass.simpleName}"
                ).toJsonText()
            }
        }

        override fun backup(request: String, callback: IBackupProgressCallback?): String {
            nativeError?.let { return it }
            val op = opOf(request)
            val startedAt = System.currentTimeMillis()
            AppLog.i(TAG, "root backup 开始：构建 rustic 请求", op = op)

            return try {
                val built = rusticRequest.build(request.parseJson<BackupOptions>())
                    ?: return "没有需要备份的内容"
                val prepared = prepareSystemItems(built)
                val rustRequest = prepared.toJsonText()

                val shared = SharedMemory.create(SHARED_MEMORY_NAME, BUFFER_SIZE)
                try {
                    val buffer = shared.mapReadWrite()
                    writeFrameAt(buffer, 0, rustRequest)

                    val poller = ProgressPoller(buffer, callback)
                    poller.start()
                    try {
                        val code = nativeBackup(buffer)
                        AppLog.i(
                            TAG,
                            "nativeBackup 返回 code=$code 耗时=${System.currentTimeMillis() - startedAt}ms",
                            op = op
                        )

                        val raw = runCatching { readFrameAt(buffer, 0) }.getOrNull()
                        val response = raw?.let {
                            runCatching { it.parseJson<OkResponse>() }.getOrNull()
                        }
                        if (code == 0 && (response == null || response.ok)) {
                            ""
                        } else {
                            response?.error.orEmpty().ifBlank { "备份失败（code=$code）" }
                        }
                    } finally {
                        poller.stop()
                        cleanupSystemItems(prepared)
                    }
                } finally {
                    shared.close()
                }
            } catch (t: Throwable) {
                AppLog.e(TAG, "backup failed", t, op)
                "备份异常：${t.message ?: t.javaClass.simpleName}"
            }
        }

        /**
         * 系统数据（Wi-Fi / 联系人 / 通话记录 / 短信）的快照不是文件：
         * 备份前先导出到一个临时目录，整个目录进快照（`as_path = "/"`，快照根就是目录内容）。
         *
         * 用目录而不是单文件是为了彩信 —— 附件是独立文件、流复制进去，多大都不占内存。
         * 一个目标可能有多个 item（短信目标下有 sms / mms 两个），各自一个目录。
         * 导出失败时把 item 标成跳过 —— Rust 侧看到空 sources 会自己跳。
         */
        private fun prepareSystemItems(request: RusticBackupRequest): RusticBackupRequest {
            val items = request.items.map { item: BackupItemDto ->
                if (item.system != true) return@map item
                val kind = SnapshotKind.of(item.stage) ?: return@map item

                val dir = systemDataDir(kind)
                if (!SystemDataExporter.exportTo(context, kind, dir)) {
                    dir.deleteRecursively()
                    return@map item.copy(skipReason = "导出失败")
                }
                item.copy(sources = listOf(dir.absolutePath), asPath = "/")
            }
            return request.copy(items = items)
        }

        /** 导出用的临时目录；每次先清空，别让上一轮的残留混进快照。 */
        private fun systemDataDir(kind: SnapshotKind): File =
            File(context.cacheDir, "sysdata_${kind.key}").apply {
                deleteRecursively()
                mkdirs()
            }

        /** 备份结束后删掉导出用的临时目录。 */
        private fun cleanupSystemItems(request: RusticBackupRequest) {
            request.items.forEach { item ->
                if (item.system != true) return@forEach
                val kind = SnapshotKind.of(item.stage) ?: return@forEach
                runCatching { File(context.cacheDir, "sysdata_${kind.key}").deleteRecursively() }
            }
        }
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

    private fun writeFrameAt(buffer: ByteBuffer, offset: Int, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        require(offset + 4 + bytes.size <= buffer.capacity()) { "请求数据过长" }

        buffer.putInt(offset, bytes.size)   // ByteBuffer 默认大端，Rust 侧按大端解析
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

}
