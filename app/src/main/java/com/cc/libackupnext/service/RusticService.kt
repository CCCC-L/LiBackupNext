package com.cc.libackupnext.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageManagerHidden
import android.content.pm.PermissionInfo
import android.graphics.Bitmap
import android.os.IBinder
import android.os.SharedMemory
import android.os.SystemClock
import android.os.UserManagerHidden
import android.system.Os
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import android.util.Log
import androidx.core.graphics.drawable.toBitmap
import com.cc.libackupnext.App
import com.cc.libackupnext.data.AppBackupSummary
import com.cc.libackupnext.data.AppPrefs
import com.cc.libackupnext.data.BackupEntry
import com.cc.libackupnext.data.BackupStep
import com.cc.libackupnext.data.BackupStepStatus
import com.cc.libackupnext.data.SnapshotMeta
import com.cc.libackupnext.data.BackupRequest
import com.cc.libackupnext.data.BackupSnapshot
import com.cc.libackupnext.data.RepoOverview
import com.cc.libackupnext.data.SnapshotOverride
import com.cc.libackupnext.data.kind
import com.cc.libackupnext.entity.AppInfo
import com.cc.libackupnext.util.ErrorLog
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import dev.rikka.tools.refine.Refine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.StringReader
import java.nio.ByteBuffer

/** 备份进度事件，state: running / done / skipped / finished */
data class BackupProgress(
    val stage: String,
    val state: String,
    val files: Long = 0,
    val reason: String = ""
)

/**
 * rustic 仓库的 service 层：对外提供备份业务方法，对内用 DAO 方法读写快照。
 *
 * 分两段：
 * - 「DAO」段（`daoXxx`，private）：只负责快照增删改查，参数是仓库地址和密码；
 * - 「业务」段（public）：读 [AppPrefs] 拿仓库信息，做分组、覆盖、删除全部、恢复校验等业务判断，
 *   返回 UI 直接可渲染的模型（[BackupEntry] / [BackupStep]）。
 *
 * Rust 侧只做快照 CRUD，不认识「覆盖备份」「删除全部」这类业务语义。
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

/**
 * 不该被备份 / 恢复的临时授权标记：
 * - `FLAG_PERMISSION_ONE_TIME`（1 << 15）：「仅本次允许」
 * - `FLAG_PERMISSION_REVOKE_WHEN_REQUESTED`（1 << 17）：下次请求时自动收回
 */
private const val TRANSIENT_PERMISSION_FLAGS = (1L shl 15) or (1L shl 17)

/** 权限表缓存时长：一次批量备份内复用，不用每个应用都重读一次。 */
private const val PERMISSION_CACHE_TTL_MS = 10_000L

object RusticService {

    private const val TAG = "RusticService"

    /** 备份源目录的绝对前缀（`*` 对应 userId / 包名）。 */
    private val BACKUP_ROOTS = listOf(
        "/data/user/*/*",
        "/data/user_de/*/*",
        "/data/media/*/Android/data/*",
        "/data/media/*/Android/obb/*"
    )

    /** 只排应用数据目录**顶层**的这些目录，子目录里的同名目录不动。 */
    private val BACKUP_EXCLUDED_NAMES = listOf(".ota", "cache", "code_cache", "no_backup", "lib")

    /**
     * 备份时排除的目录（gitignore 语义，`!` 开头表示「排除」）。
     *
     * rustic 用 gitignore 语义，`!` 开头表示「排除」。
     * 缓存、原生库、OTA 临时文件占空间大又没有恢复价值。
     */
    private val BACKUP_EXCLUDES: List<String> = BACKUP_ROOTS.flatMap { root ->
        BACKUP_EXCLUDED_NAMES.map { name -> "!$root/$name" }
    }

    /** 请求 / 响应区 512KB + 进度区 64KB：快照列表要一次塞进响应里。 */
    private const val BUFFER_SIZE = 576 * 1024
    private const val PROGRESS_OFFSET = 512 * 1024
    private const val PROGRESS_COUNTER_SIZE = 4
    private const val PROGRESS_SLOT_SIZE = 256
    private const val PROGRESS_SLOT_COUNT = (64 * 1024 - PROGRESS_COUNTER_SIZE) / PROGRESS_SLOT_SIZE
    private const val SHARED_MEMORY_NAME = "libackup-rustic"

    /** Android 上外部存储（/data/media）的 gid */
    private const val MEDIA_RW_GID = 1078

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
                        installApk(apk, packageName, userId)
                    } finally {
                        dir.deleteRecursively()
                    }

                    if (installedUid.isFailure) {
                        emit("apk", "failed")
                        return installedUid.exceptionOrNull()?.message ?: "安装失败"
                    }

                    emit("apk", "done")
                }

                val appUid: Int? =  packageUid(packageName, userId);
                for (kind in listOf("obb", "user", "user_de", "data")) {
                    val id = byKind[kind] ?: continue

                    if (appUid == null) {
                        emit(kind, "failed")
                        return "应用未安装，无法恢复数据"
                    }

                    emit(kind, "running")

                    val dest = when (kind) {
                        "obb" -> "/data/media/$userId/Android/obb"
                        "user" -> "/data/user/$userId"
                        "user_de" -> "/data/user_de/$userId"
                        else -> "/data/media/$userId/Android/data"
                    }

                    val error = restoreTo(repoPath, repoPassword, id, dest, deleteFirst)
                    if (error.isNotBlank()) {
                        emit(kind, "failed")
                        return error
                    }

                    val gid = if (kind == "data" || kind == "obb") MEDIA_RW_GID else appUid
                    fixupTree(File(dest, packageName), appUid, gid)

                    emit(kind, "done")
                }

                // 权限：把快照里的 permissions.json 解出来，逐个 pm grant
                byKind["permissions"]?.let { id ->
                    if (appUid == null && packageUid(packageName, userId) == null) {
                        emit("permissions", "skipped")
                        return@let
                    }

                    emit("permissions", "running")
                    val dir = File(context.cacheDir, "restore_permissions").apply {
                        deleteRecursively()
                        mkdirs()
                    }

                    val error = restoreTo(
                        repoPath,
                        repoPassword,
                        id,
                        dir.absolutePath,
                        delete = false
                    )
                    if (error.isNotBlank()) {
                        dir.deleteRecursively()
                        emit("permissions", "failed")
                        return error
                    }

                    val granted = runCatching {
                        JSONObject(File(dir, "permissions.json").readText()).optJSONArray("granted")
                    }.getOrNull()
                    dir.deleteRecursively()

                    if (granted == null) {
                        emit("permissions", "skipped")
                        return@let
                    }

                    // 一次 shell 里把所有 pm grant 跑完，避免每条权限都起一次 su
                    val commands = (0 until granted.length())
                        .map { granted.optString(it) }
                        .filter { it.isNotBlank() }
                        .map { "pm grant --user $userId $packageName $it" }

                    val total = commands.size
                    val failed = if (total == 0) {
                        0
                    } else {
                        val result = runCatching {
                            Shell.cmd(*commands.toTypedArray()).exec()
                        }.getOrNull()
                        if (result == null || !result.isSuccess) total else 0
                    }

                    when {
                        total == 0 -> emit("permissions", "skipped")
                        failed == 0 -> emit("permissions", "done")
                        failed == total -> {
                            Log.w(TAG, "grant permissions failed: $packageName")
                            emit("permissions", "failed")
                        }
                        else -> {
                            Log.w(TAG, "grant permissions partly failed: $packageName ($failed/$total)")
                            emit("permissions", "done")
                        }
                    }
                }

                ""
            } catch (t: Throwable) {
                Log.e(TAG, "restoreSnapshots failed", t)
                "恢复异常：${t.message ?: t.javaClass.simpleName}"
            }
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
        private fun installApk(apk: File, packageName: String, userId: Int): Result<Int> {
            val command = "pm install -r -d --user $userId ${apk.absolutePath}"

            val result = try {
                Shell.cmd(command).exec()
            } catch (t: Throwable) {
                return Result.failure(
                    IllegalStateException("执行 pm install 失败：${t.message ?: t.javaClass.simpleName}")
                )
            }

            val output = (result.out + result.err).joinToString("\n").trim()

            if (!output.contains("Success", ignoreCase = true)) {
                return Result.failure(
                    IllegalStateException("安装失败：${output.ifBlank { "pm 没有输出" }}")
                )
            }

            return Result.success(0)
        }

        /** 目标用户下这个包的 uid；没装返回 null。 */
        private fun packageUid(packageName: String, userId: Int): Int? {
            val hidden = Refine.unsafeCast<PackageManagerHidden>(context.packageManager)
            return runCatching {
                hidden.getPackageInfoAsUser(packageName, 0, userId).applicationInfo?.uid
            }.getOrNull()
        }

        /**
         * 恢复后的收尾：改归属 + 按目标目录重新打 SELinux 标签。
         *
         * 为什么必须打标签：我们的进程是 root（SELinux level 只有 `s0`），写出来的文件是
         * `app_data_file:s0`；而应用自己的进程跑在 `s0:c211,c257,...` 里，**访问这种文件会被拒**。
         * Termux 恢复后 `exec login` 报 Permission denied 就是这个原因 —— 它的 `login` 是脚本，
         * 解释器 `sh` 是 `app_data_file:s0`，应用执行时被 SELinux 挡下。
         *
         */
        private fun fixupTree(file: File, uid: Int, gid: Int) {
            if (!file.exists()) return

            val path = file.absolutePath

            // 一条 shell 里做完：取上下文 → 改归属 → 只在「上下文带 MLS 类别」时重打标签。
            // 外部存储（/data/media/.../Android/data|obb）的上下文是 media_rw_data_file:s0，
            // 本来就没有类别，root 建出来的文件也是这个，跳过可以省一次整树遍历。
            val script = "ctx=\$(ls -Zd \"$path\" | grep -o 'u:object_r:[^ ]*'); " +
                    "chown -R $uid:$gid \"$path\"; " +
                    "case \"\$ctx\" in *:c*) chcon -Rh \"\$ctx\" \"$path\";; esac"

            val result = runCatching { Shell.cmd(script).exec() }.getOrNull()
            if (result == null || !result.isSuccess) {
                // 归属改不过去应用会读不到自己的数据，必须让用户看见
                val detail = result?.err?.joinToString(" ").orEmpty()
                    .ifBlank { "退出码 ${result?.code ?: -1}" }
                Log.w(TAG, "fixup failed: $path ($detail)")
                ErrorLog.add(
                    "恢复",
                    "修改文件归属失败：$path（$detail）",
                    IllegalStateException(detail)
                )
            }
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
                val rustRequest = buildRustRequest(JSONObject(request))
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
        private fun buildRustRequest(options: JSONObject): String? {
            val packageName = options.getString("packageName")
            val userId = options.optInt("userId", 0)
            val timestamp = options.optLong("timestamp", System.currentTimeMillis())
            val userName = userNameOf(userId)

            val appName = options.optString("appName").ifBlank { packageName }
            val version = options.optString("version")
            val overrides = options.optJSONObject("overrides")

            // 类型 -> 上一次同类快照的 id，作为 rustic 的 parent 做增量备份
            val parents = options.optJSONObject("parents")

            // 拼成一个 tag：应用名|版本|用户名|时间戳
            // rustic 的 tags 是集合会排序，拆成多个就丢顺序了
            val tags = listOf(
                listOf(appName.replace("|", "/"), version, userName, timestamp.toString())
                    .joinToString("|")
            )

            // 覆盖备份时沿用旧快照的 label / tag / 备注
            fun labelOf(kind: String, fallback: String): String =
                overrides?.optJSONObject(kind)?.optString("label")?.takeIf { it.isNotBlank() }
                    ?: fallback

            fun tagsOf(kind: String, fallback: List<String>): List<String> {
                val raw = overrides?.optJSONObject(kind)?.optString("tags").orEmpty()
                if (raw.isBlank()) return fallback
                return raw.split(',').filter { it.isNotBlank() }
            }

            fun descriptionOf(kind: String): String? =
                overrides?.optJSONObject(kind)?.optString("description")?.takeIf { it.isNotBlank() }
            val items = JSONArray()

            // 图标：仓库里已经有同 label 的快照就不再备份（策略在 service 层判断）
            val includeIcon = options.optBoolean("includeIcon", true)
            if (includeIcon) writeIcon(packageName)?.let { iconPath ->
                items.put(
                    item(
                        "icon",
                        "$packageName:icon",
                        listOf(packageName),
                        listOf(iconPath),
                        asPath = File(iconPath).name
                    )
                )
            }

            // 即使某个来源暂时不存在也要带上，Rust 侧会回报「跳过」
            if (options.optBoolean("apk", false)) {
                val apk = apkPaths(packageName).firstOrNull()
                items.put(
                    item(
                        "apk",
                        labelOf("apk", "$packageName:apk"),
                        tagsOf("apk", tags),
                        listOfNotNull(apk),
                        asPath = apk?.let { File(it).name },
                        description = descriptionOf("apk")
                    )
                )
            }

            if (options.optBoolean("obb", false)) {
                val obbPath = "/data/media/$userId/Android/obb/$packageName"
                items.put(
                    item(
                        "obb",
                        labelOf("obb", "$packageName:obb"),
                        tagsOf("obb", tags),
                        listOf(obbPath),
                        asPath = packageName,
                        skipReason = skipReasonOf(obbPath),
                        description = descriptionOf("obb")
                    )
                )
            }

            if (options.optBoolean("user", false)) {
                val userDataPath = "/data/user/$userId/$packageName"
                val userDePath = "/data/user_de/$userId/$packageName"

                items.put(
                    item(
                        "user",
                        labelOf("user", "$packageName:user"),
                        tagsOf("user", tags),
                        listOf(userDataPath),
                        asPath = packageName,
                        skipReason = skipReasonOf(userDataPath),
                        description = descriptionOf("user")
                    )
                )
                items.put(
                    item(
                        "user_de",
                        labelOf("user_de", "$packageName:user_de"),
                        tagsOf("user_de", tags),
                        listOf(userDePath),
                        asPath = packageName,
                        skipReason = skipReasonOf(userDePath),
                        description = descriptionOf("user_de")
                    )
                )
            }

            if (options.optBoolean("data", false)) {
                val dataPath = "/data/media/$userId/Android/data/$packageName"
                items.put(
                    item(
                        "data",
                        labelOf("data", "$packageName:data"),
                        tagsOf("data", tags),
                        listOf(dataPath),
                        asPath = packageName,
                        skipReason = skipReasonOf(dataPath),
                        description = descriptionOf("data")
                    )
                )
            }

            // 已授予的运行时权限：写成 permissions.json 一起进仓库
            if (options.optBoolean("permissions", false)) {
                val permissionFile = writePermissions(packageName, userId)
                items.put(
                    item(
                        "permissions",
                        labelOf("permissions", "$packageName:permissions"),
                        tagsOf("permissions", tags),
                        listOfNotNull(permissionFile),
                        asPath = "permissions.json",
                        skipReason = if (permissionFile == null) "没有已授予的运行时权限" else null,
                        description = descriptionOf("permissions")
                    )
                )
            }

            if (items.length() == 0) return null

            // 把 parent 挂到对应类型的 item 上：rustic 有了显式 parent 就不用
            // 「列一遍仓库里所有快照找最新」了（那是每个类型一次的全量扫描）
            parents?.let { map ->
                for (index in 0 until items.length()) {
                    val item = items.getJSONObject(index)
                    val parent = map.optString(item.optString("stage"))
                    if (parent.isNotBlank()) item.put("parent", parent)
                }
            }

            return JSONObject().apply {
                put("path", options.getString("repoPath"))
                put("password", options.getString("repoPassword"))
                put("items", items)
                put("excludes", JSONArray(BACKUP_EXCLUDES))
                options.optString("batch").takeIf { it.isNotBlank() }?.let { put("batch", it) }
            }.toString()
        }

        private fun skipReasonOf(path: String): String? =
            if (File(path).exists()) null else "路径不存在"

        /** 用户 id 对应的用户名，拿不到就退回 user<id>。 */
        private fun userNameOf(userId: Int): String = runCatching {
            val userManager = Refine.unsafeCast<UserManagerHidden>(
                context.getSystemService(Context.USER_SERVICE)
            )
            userManager.users.firstOrNull { it.id == userId }?.name
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "user$userId"

        private fun item(
            stage: String,
            label: String,
            tags: List<String>,
            sources: List<String>,
            asPath: String?,
            skipReason: String? = null,
            description: String? = null
        ): JSONObject = JSONObject().apply {
            put("stage", stage)
            put("label", label)
            put("tags", JSONArray(tags))
            put("sources", JSONArray(sources))
            if (asPath != null) {
                put("as_path", asPath)
            }
            if (skipReason != null) {
                put("skip_reason", skipReason)
            }
            if (description != null) {
                put("description", description)
            }
        }

        /**
         * 把应用已授予的运行时权限写成 JSON，作为 permissions 快照的源文件。
         * 拿不到或一个都没授予时返回 null（Rust 侧会标记为跳过）。
         */
        private fun writePermissions(packageName: String, userId: Int): String? = runCatching {
            val granted = grantedRuntimePermissions(packageName, userId)
            if (granted.isEmpty()) return@runCatching null

            val file = File(context.cacheDir, "permissions.json")
            file.writeText(
                JSONObject().apply {
                    put("packageName", packageName)
                    put("userId", userId)
                    put("granted", JSONArray(granted))
                }.toString()
            )
            file.setLastModified(0L)
            file.absolutePath
        }.getOrNull()

        /** 权限表缓存：一次批量备份里几十个应用只读一次文件。 */
        private var permissionCacheUserId = -1
        private var permissionCacheAt = 0L
        private var permissionCache: Map<String, List<String>> = emptyMap()

        /**
         * 获取应用在用户下已赋予的运行权限
         * 文件读不到才退回 PackageManager。
         */
        private fun grantedRuntimePermissions(packageName: String, userId: Int): List<String> {
            val now = SystemClock.elapsedRealtime()
            if (userId != permissionCacheUserId || now - permissionCacheAt > PERMISSION_CACHE_TTL_MS) {
                permissionCache = runCatching {
                    parseRuntimePermissions(
                        File("/data/system/users/$userId/runtime-permissions.xml").readText()
                    )
                }.getOrDefault(emptyMap())
                permissionCacheUserId = userId
                permissionCacheAt = now
            }

            if (permissionCache.isNotEmpty()) {
                return permissionCache[packageName].orEmpty()
            }

            return runCatching {
                val hidden = Refine.unsafeCast<PackageManagerHidden>(context.packageManager)
                val info = hidden.getPackageInfoAsUser(
                    packageName,
                    PackageManager.GET_PERMISSIONS,
                    userId
                )
                (info.requestedPermissions ?: emptyArray()).filter { permission ->
                    isRuntimePermission(permission) &&
                            context.packageManager.checkPermission(permission, packageName) ==
                            PackageManager.PERMISSION_GRANTED
                }
            }.getOrDefault(emptyList())
        }

        /**
         * 解析 runtime-permissions.xml，返回「包名 -> 可恢复的运行时权限」。
         *
         * 只取 `granted="true"` 的条目；「仅本次允许」这种一次性授权（带
         * [TRANSIENT_PERMISSION_FLAGS]）会被跳过——它本来就是临时状态，
         * 恢复成永久授权反而改了用户的意思。
         * 「每次询问」的权限在文件里是 granted="false"，这里自然不收录，
         * 恢复后保持系统默认的询问行为。
         */
        private fun parseRuntimePermissions(xml: String): Map<String, List<String>> {
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(xml))

            val result = mutableMapOf<String, MutableList<String>>()
            var currentPackage: String? = null
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "pkg" -> currentPackage = parser.getAttributeValue(null, "name")

                        "item" -> {
                            val pkg = currentPackage
                            if (pkg != null &&
                                parser.getAttributeValue(null, "granted") == "true"
                            ) {
                                val flags = parser.getAttributeValue(null, "flags")
                                    ?.toLongOrNull() ?: 0L
                                // 临时授权恢复不了，也不该恢复成永久授权
                                if (flags and TRANSIENT_PERMISSION_FLAGS == 0L) {
                                    parser.getAttributeValue(null, "name")?.let { permission ->
                                        result.getOrPut(pkg) { mutableListOf() } += permission
                                    }
                                }
                            }
                        }
                    }

                    XmlPullParser.END_TAG -> if (parser.name == "pkg") currentPackage = null
                }
                event = parser.next()
            }
            return result
        }

        /** 只关心 dangerous 级别的运行时权限，其余 `pm grant` 也授不了。 */
        private fun isRuntimePermission(permission: String): Boolean = runCatching {
            val info = context.packageManager.getPermissionInfo(permission, 0)
            info.protection and PermissionInfo.PROTECTION_DANGEROUS != 0
        }.getOrDefault(false)

        /**
         * 把应用图标导出成 PNG，作为 icon 快照的源文件。
         *
         * 每次都重写，但把 mtime 归零，配合 ignore_ctime / ignore_inode，
         * rustic 会认为内容和上次一致，从而跳过重复快照。
         */
        private fun writeIcon(packageName: String): String? = runCatching {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            val bitmap = drawable.toBitmap(192, 192)

            val file = File(context.cacheDir, "icon.png")
            ByteArrayOutputStream().use { buffer ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, buffer)
                file.writeBytes(buffer.toByteArray())
            }
            file.setLastModified(0L)

            file.absolutePath
        }.getOrNull()

        private fun apkPaths(packageName: String): List<String> = runCatching {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            buildList {
                info.sourceDir?.let { add(it) }
                info.splitSourceDirs?.forEach { add(it) }
            }.filter { File(it).isFile }
        }.getOrDefault(emptyList())
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

    private val manager by lazy {
        RootServiceManager<IRusticService>(Service::class.java) {
            IRusticService.Stub.asInterface(it)
        }
    }

    private suspend fun getService(): IRusticService = manager.getService()

    /** 提前绑定 root 服务，避免点初始化时再等 su 握手。 */
    suspend fun warmUp() {
        runCatching { getService() }
            .onFailure { Log.w(TAG, "warmUp failed", it) }
    }

    /**
     * 断开并重启 root 服务进程。
     *
     * 应用升级后旧进程还跑着上一版 dex，新加的 AIDL 方法会调用失败，
     * 启动时先断一次，确保进程里跑的是当前 APK 的代码。
     */
    fun restart() {
        manager.release()
    }

    // ==================== 快照缓存 ====================

    /**
     * 每个包的备份概况（次数 + 最近一次备份 + 最新 apk 快照）。
     *
     * 不再拉「全量快照列表」：那样响应大小跟备份次数成正比，仓库大了会撑爆共享内存。
     * 概况只跟应用数量有关，200 个应用约 160KB。
     */
    suspend fun repoOverview(): List<RepoOverview> = withContext(Dispatchers.IO) {
        if (!AppPrefs.hasRepository()) return@withContext emptyList()

        try {
            val request = JSONObject().apply {
                put("path", repoPath)
                put("password", repoPassword)
            }

            val json = JSONObject(getService().overview(request.toString()))
            if (!json.optBoolean("ok", false)) {
                ErrorLog.add(
                    "备份概况",
                    json.optString("error").ifBlank { "读取备份概况失败" }
                )
                return@withContext emptyList()
            }

            val array = json.optJSONArray("packages") ?: JSONArray()
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val packageName = item.optString("package")
                    if (packageName.isBlank()) continue

                    val tags = item.optString("tags")
                    val time = item.optLong("time")
                    val description = item.optString("description").takeIf { it.isNotBlank() }

                    val snapshots = item.optJSONArray("snapshots") ?: JSONArray()
                    add(
                        RepoOverview(
                            packageName = packageName,
                            count = item.optInt("count"),
                            time = time,
                            tags = tags,
                            description = description,
                            snapshots = buildList {
                                for (i in 0 until snapshots.length()) {
                                    val snapshot = snapshots.getJSONObject(i)
                                    add(
                                        BackupSnapshot(
                                            id = snapshot.optString("id"),
                                            label = snapshot.optString("label"),
                                            // 同一次备份里这些字段都一样，Rust 侧只回传一份
                                            tags = tags,
                                            time = time,
                                            size = snapshot.optLong("size"),
                                            description = description
                                        )
                                    )
                                }
                            },
                            hasApk = item.optBoolean("has_apk"),
                            apkTags = item.optString("apk_tags").takeIf { it.isNotBlank() },
                            apkTime = item.optLong("apk_time"),
                            hasIcon = item.optBoolean("has_icon")
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            ErrorLog.e(TAG, "repoOverview failed", t)
            emptyList()
        }
    }

    /**
     * 查询仓库里的快照。
     *
     * @param packageName 传 null 表示要全部快照
     */
    private suspend fun listSnapshots(
        repoPath: String,
        repoPassword: String,
        packageName: String? = null
    ): Result<List<BackupSnapshot>> = withContext(Dispatchers.IO) {
        try {
            val request = JSONObject().apply {
                put("path", repoPath)
                put("password", repoPassword)
                put("package_name", packageName.orEmpty())
            }

            val json = JSONObject(getService().listSnapshots(request.toString()))
            if (!json.optBoolean("ok", false)) {
                return@withContext Result.failure(
                    IllegalStateException(json.optString("error").ifBlank { "查询快照失败" })
                )
            }

            val array = json.optJSONArray("snapshots") ?: JSONArray()
            val snapshots = buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        BackupSnapshot(
                            id = item.optString("id"),
                            label = item.optString("label"),
                            tags = item.optString("tags"),
                            time = item.optLong("time"),
                            size = item.optLong("size"),
                            description = item.optString("description").takeIf { it.isNotBlank() }
                        )
                    )
                }
            }
            Result.success(snapshots)
        } catch (t: Throwable) {
            ErrorLog.e(TAG, "listSnapshots failed", t)
            Result.failure(t)
        }
    }

    /** 删除一批快照。 */
    private suspend fun deleteSnapshots(
        repoPath: String,
        repoPassword: String,
        ids: List<String>
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val request = JSONObject().apply {
                put("path", repoPath)
                put("password", repoPassword)
                put("ids", JSONArray(ids))
            }
            val error = getService().deleteSnapshots(request.toString())
            if (error.isNullOrBlank()) Result.success(Unit)
            else Result.failure(IllegalStateException(error))
        } catch (t: Throwable) {
            ErrorLog.e(TAG, "deleteSnapshots failed", t)
            Result.failure(t)
        }
    }

    /**
     * 备份一个应用。
     *
     * @param onProgress 在 binder 线程回调，注意线程安全
     */
    private suspend fun runBackup(
        request: BackupRequest,
        overrides: Map<String, SnapshotOverride> = emptyMap(),
        deleteIds: List<String> = emptyList(),
        batchId: String? = null,
        iconAlreadyBackedUp: Boolean? = null,
        parentIds: Map<String, String>? = null,
        onProgress: (BackupProgress) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val callback = object : IBackupProgressCallback.Stub() {
                override fun onProgress(progress: String) {
                    runCatching {
                        val json = JSONObject(progress)
                        onProgress(
                            BackupProgress(
                                stage = json.optString("stage"),
                                state = json.optString("state"),
                                files = json.optLong("files"),
                                reason = json.optString("reason")
                            )
                        )
                    }
                }
            }

            // 图标只保留一份：仓库里已有 pkg:icon 就不再备份。
            // 批量备份时调用方已经知道答案（来自备份概况），不用再查一遍。
            val hasIcon = iconAlreadyBackedUp ?: listSnapshots(
                repoPath = request.repoPath,
                repoPassword = request.repoPassword,
                packageName = request.packageName
            ).getOrNull()?.any { it.label == "${request.packageName}:icon" } ?: false

            val payload = JSONObject().apply {
                put("repoPath", request.repoPath)
                put("repoPassword", request.repoPassword)
                put("packageName", request.packageName)
                put("appName", request.appName)
                put("version", request.version)
                put("userId", request.userId)
                put("apk", request.backupApk)
                put("obb", request.backupObb)
                put("user", request.backupUser)
                put("data", request.backupData)
                put("permissions", request.backupPermissions)
                put("timestamp", System.currentTimeMillis())
                put("includeIcon", !hasIcon)
                if (!parentIds.isNullOrEmpty()) put("parents", JSONObject(parentIds))
                // 同批次的多个应用共用一次「打开仓库 + 读索引」
                if (!batchId.isNullOrBlank()) put("batch", batchId)
                if (overrides.isNotEmpty()) {
                    put(
                        "overrides",
                        JSONObject().apply {
                            overrides.forEach { (kind, override) ->
                                put(
                                    kind,
                                    JSONObject().apply {
                                        put("label", override.label)
                                        put("tags", override.tags)
                                        if (override.description != null) {
                                            put("description", override.description)
                                        }
                                    }
                                )
                            }
                        }
                    )
                }
            }

            val error = getService().backup(payload.toString(), callback)
            if (!error.isNullOrBlank()) {
                return@withContext Result.failure(IllegalStateException(error))
            }

            // 覆盖备份：新的写成功了再删旧的
            if (deleteIds.isNotEmpty()) {
                val deleteRequest = JSONObject().apply {
                    put("path", request.repoPath)
                    put("password", request.repoPassword)
                    put("ids", JSONArray(deleteIds))
                }
                val deleteError = getService().deleteSnapshots(deleteRequest.toString())
                if (!deleteError.isNullOrBlank()) {
                    return@withContext Result.failure(IllegalStateException(deleteError))
                }
            }

            Result.success(Unit)
        } catch (t: Throwable) {
            ErrorLog.e(TAG, "backup failed", t)
            Result.failure(t)
        }
    }

    // ==================== 业务层 ====================
    // 下面是对外真正使用的方法：读 AppPrefs 里的仓库信息，做业务判断和数据处理。
    // UI 只拿这些方法的返回值渲染，不再自己拼请求、分组快照或判断勾选。

    private val repoPath: String get() = AppPrefs.repoPath
    private val repoPassword: String get() = AppPrefs.repoPassword

    /**
     * 初始化 / 校验 rustic 仓库，成功后记住地址和密码。
     *
     * @return 成功返回 Unit；失败返回带具体原因的错误。
     */
    suspend fun initRepository(path: String, password: String): Result<Unit> {
        // 空目录 / 空密码都不该建仓库：空密码等于把备份完全敞开
        if (path.isBlank()) {
            return Result.failure(IllegalStateException("请先选择仓库目录"))
        }
        if (password.isBlank()) {
            return Result.failure(IllegalStateException("请先输入仓库密码"))
        }

        return withContext(Dispatchers.IO) {
            try {
                val error = getService().initRepo(path, password)
                if (error.isNullOrBlank()) {
                    AppPrefs.saveRepository(path, password)
                    Result.success(Unit)
                } else {
                    Result.failure(IllegalStateException(error))
                }
            } catch (t: Throwable) {
                ErrorLog.e(TAG, "initRepository failed", t)
                Result.failure(t)
            }
        }
    }

    /**
     * 当前包的备份行（按备份时间分组，最新的在前）。
     *
     * 图标快照不展示，同一次备份的 apk / obb / user / user_de / data 合成一行。
     */
    suspend fun loadEntries(packageName: String, appName: String): Result<List<BackupEntry>> =
        runCatching {
            // 按包名查（rustic 侧按 label 前缀过滤），不再拉整个仓库的快照
            listSnapshots(repoPath, repoPassword, packageName)
                .getOrThrow()
                .toEntries(appName)
        }

    /**
     * 仓库里有备份的所有应用（含已安装的），[AppInfo.onlyInBackup] 统一为 true。
     *
     * 只列「有 apk 备份」的包：没有 apk 的包恢复不了，不该出现在列表里。
     * 名字和版本取自**最新那条 apk 快照**的 tag —— 最近一次备份可能没勾 apk，
     * 那时 apk 快照是更早那次、版本也更旧，跟实际能恢复的包对得上。
     */
    suspend fun backupApps(userId: Int, overview: List<RepoOverview>? = null): List<AppInfo> {
        if (!AppPrefs.hasRepository()) return emptyList()

        // 调用方已经查过概况就直接用，避免列表页一次刷新查两遍仓库
        return (overview ?: repoOverview())
            .asSequence()
            .filter { it.hasApk }
            .map { overview ->
                val tags = overview.apkTags.orEmpty()
                AppInfo(
                    name = SnapshotMeta.appName(tags)?.ifBlank { overview.packageName }
                        ?: overview.packageName,
                    packageName = overview.packageName,
                    version = SnapshotMeta.version(tags).orEmpty(),
                    versionCode = 0,
                    installTime = 0,
                    updateTime = 0,
                    userId = userId,
                    isSystem = false,
                    isFrozen = false,
                    onlyInBackup = true
                )
            }
            .toList()
    }

    /**
     * 仓库里每个有备份的包：备份次数 + 最近一次备份。
     *
     * 一次 IPC 拿到所有包的概况（Rust 侧已经分好组），不再拉全量快照列表。
     *
     * @param appNames 包名 -> 应用名，用于拼每行的标题
     */
    suspend fun backupOverview(
        appNames: Map<String, String>,
        overview: List<RepoOverview>? = null
    ): List<AppBackupSummary> =
        (overview ?: repoOverview()).map { overview ->
            val version = SnapshotMeta.version(overview.tags)
            AppBackupSummary(
                packageName = overview.packageName,
                count = overview.count,
                latest = BackupEntry(
                    title = listOfNotNull(
                        appNames[overview.packageName] ?: overview.packageName,
                        version
                    ).filter { it.isNotBlank() }.joinToString(" "),
                    time = overview.time,
                    description = overview.description,
                    version = version,
                    userName = SnapshotMeta.userName(overview.tags),
                    snapshots = overview.snapshots
                ),
                hasIcon = overview.hasIcon
            )
        }.sortedByDescending { it.latest.time }

    /**
     * 取某个包备份里的图标（应用没装时用）。
     *
     * 内部完成：找 `<包名>:icon` 快照 → 导出到临时目录 → 读出字节 → 清理。
     * 失败就说明没有图标，调用方不用再判断。
     */
    suspend fun loadIcon(packageName: String): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            val icon = listSnapshots(repoPath, repoPassword, packageName)
                .getOrNull()
                ?.firstOrNull { it.label == "$packageName:icon" }
                ?: return@withContext Result.failure(IllegalStateException("没有图标备份"))

            val dir = File(App.application.cacheDir, "icon_cache/${packageName.hashCode()}")
            dir.deleteRecursively()
            if (!dir.mkdirs()) {
                return@withContext Result.failure(IllegalStateException("无法创建缓存目录"))
            }

            try {
                val request = JSONObject().apply {
                    put("path", repoPath)
                    put("password", repoPassword)
                    put("id", icon.id)
                    put("dest", dir.absolutePath)
                }
                val error = getService().exportSnapshot(request.toString())
                if (!error.isNullOrBlank()) {
                    return@withContext Result.failure(IllegalStateException(error))
                }

                val file = dir.listFiles()?.firstOrNull { it.isFile }
                    ?: return@withContext Result.failure(IllegalStateException("图标文件不存在"))

                Result.success(file.readBytes())
            } catch (t: Throwable) {
                Log.w(TAG, "loadIcon failed: $packageName", t)
                Result.failure(t)
            } finally {
                dir.deleteRecursively()
            }
        }

    /**
     * 删除当前包下的所有快照（含图标快照），返回删除数量。
     */
    suspend fun deleteAll(packageName: String): Result<Int> {
        // 按包名查（rustic 侧按 label 前缀过滤），不要拿全量列表自己筛：
        // 全量列表有条数上限，备份次数多了会漏掉老快照，导致「删不干净」
        val snapshots = listSnapshots(repoPath, repoPassword, packageName).getOrElse {
            return Result.failure(it)
        }

        if (snapshots.isEmpty()) return Result.success(0)

        return deleteSnapshots(repoPath, repoPassword, snapshots.map { it.id })
            .map { snapshots.size }
    }

    /**
     * 回收「备份已经删光、但 `包名:icon` 还留着」的孤儿图标快照。
     *
     * 图标快照不属于任何一次备份（没有时间戳分组），所以按备份删的时候碰不到它；
     * 应用的所有备份都删完之后需要单独清理，否则仓库里会一直留着这些垃圾。
     *
     * @return 清掉的图标快照数量
     */
    suspend fun cleanOrphanIcons(): Result<Int> = withContext(Dispatchers.IO) {
        if (!AppPrefs.hasRepository()) return@withContext Result.success(0)

        try {
            val request = JSONObject().apply {
                put("path", repoPath)
                put("password", repoPassword)
            }
            val json = JSONObject(getService().cleanOrphanIcons(request.toString()))
            if (!json.optBoolean("ok", false)) {
                return@withContext Result.failure(
                    IllegalStateException(
                        json.optString("error").ifBlank { "清理孤立图标失败" }
                    )
                )
            }
            Result.success(json.optInt("removed"))
        } catch (t: Throwable) {
            ErrorLog.e(TAG, "cleanOrphanIcons failed", t)
            Result.failure(t)
        }
    }

    /** 删除一行里勾选的快照。 */
    suspend fun deleteSelected(entry: BackupEntry, kinds: Set<String>): Result<Unit> {
        val ids = entry.selectedSnapshots(kinds).map { it.id }
        if (ids.isEmpty()) {
            return Result.failure(IllegalStateException("请先勾选要删除的内容"))
        }
        return deleteSnapshots(repoPath, repoPassword, ids)
    }

    /**
     * 回收仓库空间。
     *
     * rustic 删快照只是删引用，数据要 prune 才真正释放；删完备份后调一次。
     */
    /**
     * 整理仓库：先回收孤儿图标，再回收已删除备份占用的空间。
     *
     * 只有一个入口（设置页），所以固定做彻底清理（repack 所有半空的包）。
     * 顺序很重要：先删图标快照，它占的数据块才能在同一次 prune 里被回收。
     *
     * @return 清掉的孤儿图标数量（没有就是 0）
     */
    suspend fun prune(): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val request = JSONObject().apply {
                put("path", repoPath)
                put("password", repoPassword)
            }
            // 删除孤儿图标后再整理空间
            val removedIcons = cleanOrphanIcons().getOrDefault(0)

            val error = getService().prune(request.toString())
            if (!error.isNullOrBlank()) {
                return@withContext Result.failure(IllegalStateException(error))
            }

            Result.success(removedIcons)
        } catch (t: Throwable) {
            ErrorLog.e(TAG, "prune failed", t)
            Result.failure(t)
        }
    }

    /**
     * 只算不删：估算整理仓库能回收多少字节。
     *
     * 走一遍 `prune_plan`（读索引 + 算计划），比真正执行快得多。
     */
    suspend fun estimatePrune(): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val request = JSONObject().apply {
                put("path", repoPath)
                put("password", repoPassword)
            }
            val json = JSONObject(getService().pruneEstimate(request.toString()))
            if (json.optBoolean("ok", false)) {
                Result.success(json.optLong("bytes", 0L))
            } else {
                Result.failure(
                    IllegalStateException(
                        json.optString("error").ifBlank { "估算可回收空间失败" }
                    )
                )
            }
        } catch (t: Throwable) {
            ErrorLog.e(TAG, "prune estimate failed", t)
            Result.failure(t)
        }
    }

    /** 修改一行里所有快照的备注；description 为 null 表示移除。 */
    suspend fun updateEntryDescription(entry: BackupEntry, description: String?): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val request = JSONObject().apply {
                    put("path", repoPath)
                    put("password", repoPassword)
                    put("ids", JSONArray(entry.ids))
                    if (description != null) {
                        put("description", description)
                    }
                }

                val error = getService().updateDescription(request.toString())
                if (error.isNullOrBlank()) {
                    Result.success(Unit)
                } else {
                    Result.failure(IllegalStateException(error))
                }
            } catch (t: Throwable) {
                ErrorLog.e(TAG, "updateEntryDescription failed", t)
                Result.failure(t)
            }
        }

    /** 覆盖备份按钮是否展示：应用没装就不允许覆盖。 */
    fun canOverwrite(installed: Boolean): Boolean = installed

    /** 普通备份。 */
    suspend fun backup(
        app: AppInfo,
        kinds: Set<String>,
        onProgress: (BackupProgress) -> Unit = {},
        /**
         * 批量备份的批次标识：同一批里每个应用传同一个值。
         *
         * 仓库只会在这一批的第一次打开（scrypt + 读索引 563ms），后面的应用直接复用。
         * 单独备份不传，行为和原来完全一样。
         */
        batchId: String? = null,
        /**
         * 仓库里是否已经有这个应用的图标快照。
         *
         * 批量备份时由列表页的备份概况直接给，省掉每个应用一次「查这个包的快照」的 IPC
         * （那是一次全仓库快照扫描）。传 null 就现场查一次。
         */
        iconAlreadyBackedUp: Boolean? = null,
        /**
         * 类型 -> 上一次同类快照的 id（来自备份概况）。
         *
         * 传给 rustic 当 parent：有了它就不用再列一遍全部快照去找 parent。
         * 传 null 就退回 rustic 的默认行为。
         */
        parentIds: Map<String, String>? = null
    ): Result<Unit> {
        if (kinds.isEmpty()) {
            return Result.failure(IllegalStateException("请先选择要备份的内容"))
        }
        val result = withAppPaused(app) {
            runBackup(
                request(app, kinds),
                onProgress = onProgress,
                batchId = batchId,
                iconAlreadyBackedUp = iconAlreadyBackedUp,
                parentIds = parentIds
            )
        }
        if (result.isSuccess) afterBackup()
        return result
    }

    /**
     * 覆盖备份：沿用旧快照的 label / tag / 备注，新的写成功了再删掉旧的。
     */
    suspend fun overwrite(
        app: AppInfo,
        entry: BackupEntry,
        kinds: Set<String>,
        onProgress: (BackupProgress) -> Unit = {}
    ): Result<Unit> {
        val selected = entry.selectedSnapshots(kinds)
        if (selected.isEmpty()) {
            return Result.failure(IllegalStateException("请先勾选要覆盖的内容"))
        }

        val overrides = selected.associate {
            it.kind() to SnapshotOverride(it.label, it.tags, it.description)
        }
        // user_de 快照由 user 这一步产生，备份请求里按 user 传
        val backupKinds = selected.map { if (it.kind() == "user_de") "user" else it.kind() }.toSet()

        val result = withAppPaused(app) {
            runBackup(
                request = request(app, backupKinds),
                overrides = overrides,
                deleteIds = selected.map { it.id },
                onProgress = onProgress
            )
        }
        if (result.isSuccess) afterBackup()
        return result
    }

    /**
     * 备份期间暂停目标应用：给它所有进程发 SIGSTOP，文件就不会再被改写。
     *
     * 备份结束（成功或失败）立刻 SIGCONT 恢复；同时记一份到 AppPrefs，
     * 万一进程被杀，下次启动会把残留的暂停恢复掉。
     */
    private suspend fun <T> withAppPaused(app: AppInfo, block: suspend () -> T): T {
        val key = "${app.packageName}|${app.userId}"
        val paused = AppManagerService.pauseApp(app.packageName, app.userId)
        if (paused) AppPrefs.addPausedApp(key)

        return try {
            block()
        } finally {
            if (paused) {
                AppManagerService.resumeApp(app.packageName, app.userId)
                AppPrefs.removePausedApp(key)
            }
        }
    }

    /** 备份成功后的收尾：按设置复制自身安装包。 */
    private suspend fun afterBackup() {
        copySelfApkIfNeeded()
    }

    /** 备份自身：把 LiBackupNext 的安装包复制到仓库根目录，方便从仓库里装回来。 */
    private suspend fun copySelfApkIfNeeded() = withContext(Dispatchers.IO) {
        if (!AppPrefs.backupSelf) return@withContext
        val dir = repoPath
        if (dir.isBlank()) return@withContext

        runCatching {
            val app = App.application
            val source = app.applicationInfo.sourceDir
            val version = app.packageManager
                .getPackageInfo(app.packageName, 0)
                .versionName
                .orEmpty()
                .ifBlank { "unknown" }
            val dest = "$dir/LiBackupNext-$version.apk"

            // 仓库一般在 /storage 下，应用进程没有写权限，交给 root 复制
            if (Shell.cmd("test -f \"$dest\"").exec().isSuccess) return@runCatching

            val result = Shell.cmd("cp -f \"$source\" \"$dest\"").exec()
            if (result.isSuccess) {
                Log.i(TAG, "copySelfApk: $dest")
            } else {
                Log.w(TAG, "copySelfApk failed: ${result.err.joinToString()}")
            }
        }.onFailure { Log.w(TAG, "copySelfApk failed", it) }
    }

    /**
     * 恢复前的检查：返回要提示给用户的原因，null 表示可以恢复。
     */
    fun restoreBlockedReason(
        entry: BackupEntry,
        kinds: Set<String>,
        installed: Boolean
    ): String? {
        val selected = entry.selectedSnapshots(kinds)
        if (selected.isEmpty()) return "请先勾选要恢复的内容"

        val hasApk = selected.any { it.kind() == "apk" }
        val needsApp = selected.any { it.kind() != "apk" }
        return if (needsApp && !hasApk && !installed) "应用未安装，无法恢复数据" else null
    }

    /**
     * 恢复一行里勾选的快照。
     *
     * @param installed 应用当前是否已安装（未安装且没勾 apk 时无法恢复数据）
     */
    suspend fun restore(
        app: AppInfo,
        entry: BackupEntry,
        kinds: Set<String>,
        installed: Boolean,
        onProgress: (BackupProgress) -> Unit = {}
    ): Result<Unit> {
        val selected = entry.selectedSnapshots(kinds)
        restoreBlockedReason(entry, kinds, installed)?.let {
            return Result.failure(IllegalStateException(it))
        }

        // 恢复前先停掉应用：一边写一边被覆盖会拿到半新半旧的数据
        AppManagerService.stopQuietly(app.packageName, app.userId)

        return withContext(Dispatchers.IO) {
            try {
                val request = JSONObject().apply {
                    put("repoPath", repoPath)
                    put("repoPassword", repoPassword)
                    put("userId", app.userId)
                    put("packageName", app.packageName)
                    put("delete", true)
                    put(
                        "items",
                        JSONArray().apply {
                            selected.forEach { snapshot ->
                                put(JSONObject().put("kind", snapshot.kind()).put("id", snapshot.id))
                            }
                        }
                    )
                }
                val callback = object : IBackupProgressCallback.Stub() {
                    override fun onProgress(progress: String) {
                        runCatching {
                            val json = JSONObject(progress)
                            onProgress(
                                BackupProgress(
                                    stage = json.optString("stage"),
                                    state = json.optString("state"),
                                    files = json.optLong("files"),
                                    reason = json.optString("reason")
                                )
                            )
                        }
                    }
                }

                val error = getService().restoreSnapshots(request.toString(), callback)
                if (error.isNullOrBlank()) Result.success(Unit)
                else Result.failure(IllegalStateException(error))
            } catch (t: Throwable) {
                ErrorLog.e(TAG, "restore failed", t)
                Result.failure(t)
            }
        }
    }

    // ==================== 进度步骤 ====================

    /** 备份要显示的步骤（顺序固定）。 */
    fun backupSteps(kinds: Set<String>): List<BackupStep> = buildList {
        if ("apk" in kinds) {
            add("apk")
            // obb 跟着 apk 一起备份
            add("obb")
        }
        if ("user" in kinds) {
            add("user")
            add("user_de")
            add("permissions")
        }
        if ("data" in kinds) add("data")
    }.map { BackupStep(it, BackupStepStatus.WAITING) }

    /** 恢复要显示的步骤：只列出真正勾选到的快照。 */
    fun restoreSteps(entry: BackupEntry, kinds: Set<String>): List<BackupStep> {
        val selected = entry.selectedSnapshots(kinds)
        return buildList {
            if (selected.any { it.kind() == "apk" }) add("apk")
            if (selected.any { it.kind() == "obb" }) add("obb")
            if (selected.any { it.kind() == "user" }) add("user")
            if (selected.any { it.kind() == "user_de" }) add("user_de")
            if (selected.any { it.kind() == "permissions" }) add("permissions")
            if (selected.any { it.kind() == "data" }) add("data")
        }.map { BackupStep(it, BackupStepStatus.WAITING) }
    }

    /** 把一条进度事件合并进步骤列表。 */
    fun applyProgress(steps: List<BackupStep>, progress: BackupProgress): List<BackupStep> {
        if (progress.stage.isBlank()) return steps
        return steps.map { step ->
            if (step.key != progress.stage) {
                step
            } else {
                step.copy(
                    status = statusOf(progress.state, step.status),
                    files = progress.files,
                    reason = progress.reason
                )
            }
        }
    }

    /** 失败时把还没跑完的步骤标成失败。 */
    fun markFailed(steps: List<BackupStep>): List<BackupStep> = steps.map {
        if (it.status == BackupStepStatus.WAITING || it.status == BackupStepStatus.RUNNING) {
            it.copy(status = BackupStepStatus.FAILED)
        } else {
            it
        }
    }

    // ==================== 内部 ====================

    private fun request(app: AppInfo, kinds: Set<String>) = BackupRequest(
        repoPath = repoPath,
        repoPassword = repoPassword,
        packageName = app.packageName,
        appName = app.name,
        version = app.version,
        userId = app.userId,
        backupApk = "apk" in kinds,
        backupObb = "apk" in kinds,
        backupUser = "user" in kinds,
        backupData = "data" in kinds,
        backupPermissions = "user" in kinds
    )

    private fun statusOf(state: String, fallback: BackupStepStatus): BackupStepStatus =
        when (state) {
            "running" -> BackupStepStatus.RUNNING
            "done" -> BackupStepStatus.DONE
            "skipped" -> BackupStepStatus.SKIPPED
            else -> fallback
        }

    /** 快照 -> 备份行：去掉 icon，同一次备份（时间戳相同）的合成一行。 */
    private fun List<BackupSnapshot>.toEntries(appName: String): List<BackupEntry> =
        asSequence()
            .filterNot { it.label.endsWith(":icon") }
            .groupBy { SnapshotMeta.timestamp(it.tags) ?: it.time }
            .map { (time, group) ->
                val version = group.firstNotNullOfOrNull { SnapshotMeta.version(it.tags) }
                BackupEntry(
                    title = listOfNotNull(appName, version)
                        .filter { it.isNotBlank() }
                        .joinToString(" "),
                    time = time,
                    description = group.firstNotNullOfOrNull { it.description },
                    version = version,
                    userName = group.firstNotNullOfOrNull { SnapshotMeta.userName(it.tags) },
                    snapshots = group
                )
            }
            .sortedByDescending { it.time }
            .toList()
}
