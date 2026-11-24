package com.cc.libackupnext.service.root

import android.app.AppOpsManager
import android.app.AppOpsManagerHidden
import android.app.usage.UsageStatsManagerHidden
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PackageManagerHidden
import android.content.pm.PermissionInfo
import android.os.IDeviceIdleController
import android.os.ServiceManager
import android.os.UserHandle
import android.util.Log
import com.cc.libackupnext.util.AppLog
import com.android.internal.app.IAppOpsService
import com.cc.libackupnext.data.json.AppOpsFile
import com.cc.libackupnext.data.json.AppStateFile
import com.cc.libackupnext.service.json.parseJson
import com.cc.libackupnext.service.json.toJsonText
import com.topjohnwu.superuser.Shell
import dev.rikka.tools.refine.Refine
import java.io.File

/** 本文件统一的日志 tag。 */
private const val TAG = "AppState"

/** 待机分组：完全允许后台行为。只有系统（电池白名单）能给，应用自己 set 会抛异常。 */
private const val STANDBY_BUCKET_EXEMPTED = 5

/**
 * 应用状态（运行时权限 / appops / 电池优化白名单 / 待机分组）的备份与恢复。
 * 所有调用都必须在 RootService 的 uid 0 进程里执行。
 */
internal class AppState(private val context: Context) {

    /**
     * 把运行时权限、appops、电池优化白名单、应用待机分组写成 JSON，作为 permissions 快照的源文件。
     */
    fun write(packageName: String, userId: Int): String? = runCatching {
        val info = Refine.unsafeCast<PackageManagerHidden>(context.packageManager)
            .getPackageInfoAsUser(packageName, PackageManager.GET_PERMISSIONS, userId)

        val requested = (info.requestedPermissions ?: emptyArray())
            .filter { isRuntimePermission(it) }
        val granted = requested.filter {
            context.packageManager.checkPermission(it, packageName) ==
                    PackageManager.PERMISSION_GRANTED
        }
        val appops = collectAppOps(packageName, userId)
        val whitelisted = isBatteryWhitelisted(packageName)
        val bucket = standbyBucket(packageName)

        if (granted.isEmpty() && appops == null && !whitelisted) return@runCatching null

        val file = File(context.cacheDir, "permissions.json")
        file.writeText(
            AppStateFile(
                packageName = packageName,
                userId = userId,
                granted = granted,
                requested = requested,
                appops = appops,
                batteryWhitelist = whitelisted,
                standbyBucket = bucket
            ).toJsonText()
        )
        file.setLastModified(0L)
        file.absolutePath
    }.getOrNull()

    // ==================== 权限 / appops / 电池白名单 ====================
    private val appOpsManager by lazy {
        Refine.unsafeCast<AppOpsManagerHidden>(context.getSystemService(Context.APP_OPS_SERVICE))
    }

    private val appOpsService by lazy {
        IAppOpsService.Stub.asInterface(ServiceManager.getService("appops"))
    }

    private val idleController by lazy {
        IDeviceIdleController.Stub.asInterface(ServiceManager.getService("deviceidle"))
    }

    /** 把一份 PackageOps 列表摊平成「op -> 模式」。 */
    private fun readOps(entries: List<AppOpsManagerHidden.PackageOps>?): Map<Int, Int> {
        val result = HashMap<Int, Int>()
        for (packageOps in entries.orEmpty()) {
            for (entry in packageOps.ops) {
                result[entry.op] = entry.mode
            }
        }
        return result
    }

    /** 应用显式设置过的 appops，包级和 uid 级分开存；都没有就返回 null。 */
    private fun collectAppOps(packageName: String, userId: Int): AppOpsFile? = runCatching {
        val uid = packageUid(context, packageName, userId) ?: return@runCatching null
        // 传 null 表示全部 op —— 用 getOpStrs() 的下标当 op code 会漏掉高位 op
        val packageOps = readOps(appOpsManager.getOpsForPackage(uid, packageName, null))
        val uidOps = readOps(appOpsService.getUidOps(uid, null))

        // JSON 对象的 key 只能是字符串，op code 转成十进制字符串
        fun dump(source: Map<Int, Int>): Map<String, Int> = source
            .filterValues { it != AppOpsManager.MODE_DEFAULT }
            .mapKeys { (op, _) -> op.toString() }

        val packageModes = dump(packageOps)
        val uidModes = dump(uidOps)
        if (packageModes.isEmpty() && uidModes.isEmpty()) {
            null
        } else {
            AppOpsFile(packageModes = packageModes, uid = uidModes)
        }
    }.onFailure {
        // 权限/appops 读不到会被静默当成「没有」，恢复后表现就是功能异常
        AppLog.w(TAG, "collectAppOps 失败（该项会被当作空）", it)
    }.getOrNull()

    private val usageStats by lazy {
        Refine.unsafeCast<UsageStatsManagerHidden>(
            context.getSystemService(Context.USAGE_STATS_SERVICE)
        )
    }

    /** 应用待机分组 */
    private fun standbyBucket(packageName: String): Int =
        runCatching { usageStats.getAppStandbyBucket(packageName) }.getOrDefault(-1)

    private fun isBatteryWhitelisted(packageName: String): Boolean =
        runCatching { idleController.isPowerSaveWhitelistApp(packageName) }
            .onFailure { Log.w(TAG, "读白名单失败", it) }.getOrDefault(false)

    /**
     * 全量写回权限 / appops / 白名单，返回失败的权限条数。
     * 备份里有的恢复成备份的样子，备份里没有的（现在多出来的）权限撤掉、appops 设回默认、白名单移除。
     */
    fun apply(text: String, packageName: String, uid: Int): Int {
        val state = text.parseJson<AppStateFile>()
        val user = UserHandle.getUserHandleForUid(uid)
        val manager = Refine.unsafeCast<PackageManagerHidden>(context.packageManager)

        val granted = state.granted.filter { it.isNotBlank() }.toSet()
        // 旧快照没有 requested，只按 granted 授权，不做撤销
        val requested = state.requested.filter { it.isNotBlank() }.toSet().ifEmpty { granted }

        var failed = 0
        for (permission in requested) {
            val result = runCatching {
                if (permission in granted) {
                    manager.grantRuntimePermission(packageName, permission, user)
                } else {
                    manager.revokeRuntimePermission(packageName, permission, user, "restore")
                }
            }
            if (result.isFailure) failed++
        }

        // 这三类失败不进 failed（它只数权限），单独汇总
        var degraded = applyAppOps(state.appops, packageName, uid)

        // 旧快照没有这个字段，不能当成 false —— 那会把白名单删掉
        state.batteryWhitelist?.let { wantWhitelist ->
            runCatching {
                if (wantWhitelist) {
                    idleController.addPowerSaveWhitelistApp(packageName)
                } else {
                    idleController.removePowerSaveWhitelistApp(packageName)
                }
            }.onFailure {
                Log.w(TAG, "白名单写入失败（备份=$wantWhitelist）", it)
                degraded++
            }
        }

        // 待机分组
        val bucket = state.standbyBucket
        if (bucket >= 0 && bucket != STANDBY_BUCKET_EXEMPTED) {
            runCatching { usageStats.setAppStandbyBucket(packageName, bucket) }
                .onFailure {
                    Log.w(TAG, "setAppStandbyBucket=$bucket 失败", it)
                    degraded++
                }
        }

        // 逐条只留 logcat，落盘一条汇总
        if (degraded > 0) {
            AppLog.w(TAG, "$packageName 有 $degraded 项系统设置未写回（白名单/待机分组/appops）")
        }

        return failed
    }

    /** appops 全量写回：逐条比对，不一样的才写，备份里没有的设回默认。返回失败条数。 */
    private fun applyAppOps(state: AppOpsFile?, packageName: String, uid: Int): Int {
        if (state == null) return 0

        fun modes(section: Map<String, Int>): Map<Int, Int> = section
            .mapNotNull { (key, mode) -> key.toIntOrNull()?.let { op -> op to mode } }
            .toMap()

        val wantPackage = modes(state.packageModes)
        val wantUid = modes(state.uid)
        val currentPackage = readOps(appOpsManager.getOpsForPackage(uid, packageName, null))
        val currentUid = readOps(appOpsService.getUidOps(uid, null))

        // 备份里有的 + 当前有的，并集就是要处理的全部 op（含 10000+ 的高位 op）
        val opCodes = sortedSetOf<Int>().apply {
            addAll(wantPackage.keys)
            addAll(wantUid.keys)
            addAll(currentPackage.keys)
            addAll(currentUid.keys)
        }

        var written = 0
        var failed = 0
        for (op in opCodes) {
            val name = op.toString()

            val packageTarget = wantPackage[op] ?: AppOpsManager.MODE_DEFAULT
            if ((currentPackage[op] ?: AppOpsManager.MODE_DEFAULT) != packageTarget) {
                runCatching { appOpsManager.setMode(op, uid, packageName, packageTarget) }
                    .onFailure {
                        Log.w(TAG, "setMode $name=$packageTarget 失败", it)
                        failed++
                    }
                    .onSuccess { written++ }
            }

            val uidTarget = wantUid[op] ?: AppOpsManager.MODE_DEFAULT
            // uid 段的 ignore 基本都是「权限没授予」的镜像，设回默认系统会立刻同步回来，
            if (uidTarget == AppOpsManager.MODE_DEFAULT &&
                currentUid[op] == AppOpsManager.MODE_IGNORED
            ) {
                continue
            }
            if ((currentUid[op] ?: AppOpsManager.MODE_DEFAULT) != uidTarget) {
                runCatching { appOpsManager.setUidMode(op, uid, uidTarget) }
                    .onFailure {
                        Log.w(TAG, "setUidMode $name=$uidTarget 失败", it)
                        failed++
                    }
                    .onSuccess { written++ }
            }
        }
        // 每个应用一遍，只在写入成功时留痕
        if (written > 0) {
            Log.d(
                TAG,
                "appops $packageName uid=$uid 写入 $written 条 | " +
                        "目标 pkg=${wantPackage.size} uid=${wantUid.size} | " +
                        "当前 pkg=${currentPackage.size} uid=${currentUid.size}"
            )
        }
        return failed
    }

    /** 只关心 dangerous 级别的运行时权限 */
    private fun isRuntimePermission(permission: String): Boolean = runCatching {
        val info = context.packageManager.getPermissionInfo(permission, 0)
        info.protection and PermissionInfo.PROTECTION_DANGEROUS != 0
    }.getOrDefault(false)
}

/** 目标用户下这个包的 uid；没装返回 null。 */
internal fun packageUid(context: Context, packageName: String, userId: Int): Int? {
    val hidden = Refine.unsafeCast<PackageManagerHidden>(context.packageManager)
    return runCatching {
        hidden.getPackageInfoAsUser(packageName, 0, userId).applicationInfo?.uid
    }.getOrNull()
}

/** `pm install-create` 成功时输出里的会话 id：`Success: created install session [1234567]`。 */
private val INSTALL_SESSION_ID = Regex("""\[(\d+)]""")

/**
 * 把快照里的 APK 装到指定用户。
 *
 * 失败时返回的异常只带原因（pm 的输出），「安装失败：」前缀由调用方拼 ——
 * 和 [runShellChecked] 的约定一致，免得同一条文案里出现两次「失败」。
 */
internal fun installApks(apks: List<File>, userId: Int): Result<Int> {
    if (apks.isEmpty()) {
        return Result.failure(IllegalStateException("没有可安装的 APK"))
    }

    return runCatching {
        // pm 系列失败时退出码也可能是 0，成败一律看输出里的关键字
        val createOutput = runShellChecked("pm install-create -r -d --user $userId", "创建安装会话失败")
        val session = INSTALL_SESSION_ID.find(createOutput)?.groupValues?.get(1)
            ?: throw IllegalStateException("会话 id 缺失：$createOutput")

        for (apk in apks) {
            val path = "'" + apk.absolutePath.replace("'", "'\\''") + "'"
            try {
                runShellChecked(
                    command = "pm install-write -S ${apk.length()} $session '${apk.name}' $path",
                    failText = "写入 APK 失败",
                    expect = "Success",
                )
            } catch (t: Throwable) {
                // 写失败就把会话丢掉，别在系统里留个半截 session
                runShell("pm install-abandon $session")
                throw t
            }
        }

        runShellChecked("pm install-commit $session", "安装失败", expect = "Success")
        0
    }
}

/** 外部存储（/data/media 与 SD 卡）的属组：media_rw。 */
internal const val MEDIA_RW_GID = 1023

/** MediaProvider 的包名 */
private const val MEDIA_PROVIDER_PACKAGE = "com.android.providers.media.module"

/** 模拟卷路径：`/storage/emulated/<userId>/…` 或 `/data/media/<userId>/…`。 */
private val EMULATED_VOLUME = Regex("""^/(?:storage/emulated|data/media)/(\d+)(?:/|$)""")

/**
 * 外部存储里的路径该归谁：模拟卷是 MediaProvider 代写的，属主就是它；SD 卡属主 media_rw。
 * 属组一律 media_rw。取不到 MediaProvider 就退回 media_rw —— 归属不完美，但不影响恢复成功。
 */
internal fun externalStorageOwner(context: Context, path: String): Pair<Int, Int> {
    val userId = EMULATED_VOLUME.find(path)?.groupValues?.get(1)?.toIntOrNull()
    val uid = userId?.let { packageUid(context, MEDIA_PROVIDER_PACKAGE, it) }
    if (userId != null && uid == null) {
        AppLog.w(TAG, "找不到 MediaProvider，外部存储归属退回 media_rw")
    }
    return (uid ?: MEDIA_RW_GID) to MEDIA_RW_GID
}

/**
 * 恢复后修正归属和 SELinux 上下文。
 *
 * @param parentContext 用**父目录**的上下文而不是自己的。
 *   外部存储（/data/media/0/...）必须传 true —— 那里的文件不该带 MLS 类别，
 *   带上了就只有「类别相同」的那个应用能访问，别的应用连删除都做不到。
 *   应用数据（/data/user/0/<pkg>）要保持 false，它的类别是按 uid 算的，必须有。
 * @param dirMode 递归设置所有**目录**的权限，例如外部存储用户目录要 `2770`。
 *   文件不动 —— 删文件只要求父目录可写，文件本身保持 rustic 恢复出来的权限。
 */
internal fun fixupTree(
    file: File,
    uid: Int,
    gid: Int,
    parentContext: Boolean,
    dirMode: String?
) {
    if (!file.exists()) return

    val path = file.absolutePath
    val ctxSource = if (parentContext) {
        file.parentFile?.absolutePath ?: path
    } else {
        path
    }

    // 外部存储强制刷成父目录的 ctx（无类别）；应用数据只在上下文带类别时才刷
    val chcon = if (parentContext) {
        "chcon -Rh \"\$ctx\" \"$path\""
    } else {
        "case \"\$ctx\" in *:c*) chcon -Rh \"\$ctx\" \"$path\";; esac"
    }
    val script = buildString {
        append("ctx=\$(ls -Zd \"$ctxSource\" | grep -o 'u:object_r:[^ ]*'); ")
        append("chown -R $uid:$gid \"$path\"; ")
        append(chcon)
        if (dirMode != null) {
            append("; find \"$path\" -type d -exec chmod $dirMode {} +")
        }
    }

    val result = runCatching { Shell.cmd(script).exec() }.getOrNull()
    if (result == null || !result.isSuccess) {
        // 归属改不过去应用会读不到自己的数据，必须让用户看见
        val detail = result?.err?.joinToString(" ").orEmpty()
            .ifBlank { "退出码 ${result?.code ?: -1}" }
        AppLog.e("恢复", "修改文件归属失败：$path（$detail）", IllegalStateException(detail))
    }
}
