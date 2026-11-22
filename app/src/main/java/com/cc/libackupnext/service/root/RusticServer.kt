package com.cc.libackupnext.service.root

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.SharedMemory
import android.util.Log
import com.cc.libackupnext.data.BackupProgress
import com.cc.libackupnext.data.MediaPath
import com.cc.libackupnext.data.SnapshotKind
import com.cc.libackupnext.data.json.BackupOptions
import com.cc.libackupnext.data.json.ExportSnapshotRequest
import com.cc.libackupnext.data.json.InitRepoRequest
import com.cc.libackupnext.data.json.OkResponse
import com.cc.libackupnext.data.json.RestoreSnapshotsRequest
import com.cc.libackupnext.data.json.StatDirsRequest
import com.cc.libackupnext.data.json.StatDirsResponse
import com.cc.libackupnext.data.json.SystemDataCountsResponse
import com.cc.libackupnext.service.IBackupProgressCallback
import com.cc.libackupnext.service.IRusticService
import com.cc.libackupnext.service.json.parseJson
import com.cc.libackupnext.service.json.toJsonText
import com.cc.libackupnext.service.root.sysdata.SystemDataExporter
import com.cc.libackupnext.service.root.sysdata.SystemDataRestorer
import com.cc.libackupnext.util.AppLog
import com.topjohnwu.superuser.ipc.RootService
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

/**
 * root 侧的 rustic 服务：`RootService` + JNI 桥接 + 快照增删改查。
 *
 * 主进程用的入口在 `service.gateway.RusticRepoGateway`：那边负责绑定服务、拼请求、
 * 把结果整理成 UI 能渲染的模型。
 * 这里只管「把 JSON 丢进共享内存、调 native、把结果 JSON 拿回来」。
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

    /** /data/media/0/Android/data 的 gid（ext_data_rw）。 */
    private const val EXT_DATA_RW_GID = 1078

    /** /data/media/0/Android/obb 的 gid（ext_obb_rw）。*/
    private const val EXT_OBB_RW_GID = 1079

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
        // libsu 不会实例化 Application，:root 的日志只能在这里装（install 幂等）
        override fun onCreate() {
            AppLog.install(applicationContext)
        }

        override fun onBind(intent: Intent): IBinder = Impl(applicationContext).apply { onBind() }
    }

    private class Impl(private val context: Context) : IRusticService.Stub() {

        private val appState = AppState(context)
        private val rusticRequest = RusticRequestBuilder(context, appState)

        private var nativeError: String? = null

        /**
         * 恢复的底层动作。用 getter 而不是 lazy：`nativeError` 要等 onBind 才赋值，
         * lazy 会把它第一次读到的 null 缓存下来，native 库后来加载失败就短路不了。
         */
        private val restoreSupport: RusticRestoreSupport
            get() = RusticRestoreSupport(context, nativeError, ::nativeRestore)

        /** 备份时的系统数据导出 / 清理。无状态，直接用。 */
        private val backupSupport: RusticBackupSupport
            get() = RusticBackupSupport(context)

        /** 简单请求：转给 [runSimpleNative]，把 native 库错误一并带过去。 */
        private fun runSimple(
            request: String,
            native: (ByteBuffer) -> Int,
            failText: String = "操作失败"
        ): String = runSimpleNative(request, native, nativeError, failText)

        /** 查询请求：转给 [runQueryNative]，结果 JSON 原样回给主进程。 */
        private fun runQuery(
            request: String,
            native: (ByteBuffer) -> Int,
            failText: String = "查询失败"
        ): String = runQueryNative(request, native, nativeError, failText)

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
         * 和目录恢复走同一个 [RusticRestoreSupport.restoreTree]，区别只是快照里只有一个文件、没有顶层目录名，
         * 所以解包目录本身就是目标。
         */
        override fun exportSnapshot(request: String): String {
            val options = request.parseJson<ExportSnapshotRequest>()
            val dest = File(options.dest)
            val uid = context.applicationInfo.uid

            return restoreSupport.restoreTree(
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

        /**
         * 批量判断目录是否存在。
         *
         * 主进程（普通 uid）只看得到自己用户的空间，别的用户的目录只能由 root 判；
         * 传进来的路径是物理路径，`File.isDirectory` 直接读 /data/media/<userId>/…。
         */
        override fun statDirs(request: String): String = try {
            val paths = request.parseJson<StatDirsRequest>().paths
            val existing = paths.filter { it.isNotBlank() && File(it).isDirectory }
            StatDirsResponse(ok = true, existing = existing).toJsonText()
        } catch (t: Throwable) {
            AppLog.e(TAG, "判断目录是否存在失败", t)
            OkResponse(ok = false, error = t.message ?: "判断目录是否存在失败").toJsonText()
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
                    // 归属照系统自己写的来：模拟卷归 MediaProvider、SD 卡归 media_rw，属组一律 media_rw。
                    val target = File(MediaPath.physical(packageName))
                    val (uid, gid) = externalStorageOwner(context, target.absolutePath)
                    val error = restoreSupport.restoreTree(
                        snapshotId = id,
                        target = target,
                        stage = SnapshotKind.DIR.key,
                        delete = false,
                        uid = uid,
                        gid = gid,
                        parentContext = true,
                        dirMode = "2770",
                        onProgress = callback
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

                    val error = restoreSupport.restoreTo(
                        id,
                        dir.absolutePath,
                        delete = false,
                        stage = SnapshotKind.APK.key,
                        onProgress = callback
                    )
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
                        restoreSupport.loadJsonFromRepo(id, SnapshotKind.PERMISSIONS.key)
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
                    val error = restoreSupport.restoreTree(
                        snapshotId = id,
                        target = File(spec.dir, packageName),
                        stage = kind.key,
                        delete = deleteFirst,
                        uid = appUid,
                        gid = spec.gid,
                        dirMode = spec.dirMode,
                        onProgress = callback
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
                        val dir = restoreSupport.loadSystemDirFromRepo(id, kind)
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

        override fun backup(request: String, callback: IBackupProgressCallback?): String {
            nativeError?.let { return it }
            val op = opOf(request)
            val startedAt = System.currentTimeMillis()
            AppLog.i(TAG, "root backup 开始：构建 rustic 请求", op = op)

            return try {
                val built = rusticRequest.build(request.parseJson<BackupOptions>())
                    ?: return "没有需要备份的内容"
                val prepared = backupSupport.prepareSystemItems(built)
                val rustRequest = prepared.toJsonText()

                val shared = SharedMemory.create(RUSTIC_SHARED_MEMORY_NAME, RUSTIC_BUFFER_SIZE)
                try {
                    val buffer = shared.mapReadWrite()
                    writeFrameAt(buffer, 0, rustRequest)

                    val poller = RusticProgressPoller(buffer, callback)
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
                        backupSupport.cleanupSystemItems(prepared)
                    }
                } finally {
                    shared.close()
                }
            } catch (t: Throwable) {
                AppLog.e(TAG, "backup failed", t, op)
                "备份异常：${t.message ?: t.javaClass.simpleName}"
            }
        }

    }
}
