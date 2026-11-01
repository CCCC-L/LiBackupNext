package com.cc.libackupnext.service.util

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
import com.android.internal.app.IAppOpsService
import com.cc.libackupnext.util.ErrorLog
import com.topjohnwu.superuser.Shell
import dev.rikka.tools.refine.Refine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.collections.iterator

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
            JSONObject().apply {
                put("packageName", packageName)
                put("userId", userId)
                put("granted", JSONArray(granted))
                put("requested", JSONArray(requested))
                if (appops != null) put("appops", appops)
                put("batteryWhitelist", whitelisted)
                put("standbyBucket", bucket)
            }.toString()
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
    private fun collectAppOps(packageName: String, userId: Int): JSONObject? = runCatching {
        val uid = packageUid(context, packageName, userId) ?: return@runCatching null
        // 传 null 表示全部 op —— 用 getOpStrs() 的下标当 op code 会漏掉高位 op
        val packageOps = readOps(appOpsManager.getOpsForPackage(uid, packageName, null))
        val uidOps = readOps(appOpsService.getUidOps(uid, null))

        fun dump(source: Map<Int, Int>): JSONObject = JSONObject().apply {
            for ((op, mode) in source) {
                if (mode == AppOpsManager.MODE_DEFAULT) continue
                put(op.toString(), mode)
            }
        }

        val packageJson = dump(packageOps)
        val uidJson = dump(uidOps)
        if (packageJson.length() == 0 && uidJson.length() == 0) {
            null
        } else {
            JSONObject().put("package", packageJson).put("uid", uidJson)
        }
    }.onFailure { Log.w(TAG, "collectAppOps 失败", it) }.getOrNull()

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
    fun apply(json: JSONObject, packageName: String, uid: Int): Int {
        val user = UserHandle.getUserHandleForUid(uid)
        val manager = Refine.unsafeCast<PackageManagerHidden>(context.packageManager)

        val granted = json.optJSONArray("granted").toStringSet()
        // 旧快照没有 requested，只按 granted 授权，不做撤销
        val requested = json.optJSONArray("requested").toStringSet().ifEmpty { granted }

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

        applyAppOps(json.optJSONObject("appops"), packageName, uid)

        // 旧快照没有这个字段，不能当成 false —— 那会把白名单删掉
        if (json.has("batteryWhitelist")) {
            val wantWhitelist = json.getBoolean("batteryWhitelist")
            runCatching {
                if (wantWhitelist) {
                    idleController.addPowerSaveWhitelistApp(packageName)
                } else {
                    idleController.removePowerSaveWhitelistApp(packageName)
                }
            }.onFailure { Log.w(TAG, "白名单写入失败（备份=$wantWhitelist）", it) }
        }

        // 待机分组
        val bucket = json.optInt("standbyBucket", -1)
        if (bucket >= 0 && bucket != STANDBY_BUCKET_EXEMPTED) {
            runCatching { usageStats.setAppStandbyBucket(packageName, bucket) }
                .onFailure { Log.w(TAG, "setAppStandbyBucket=$bucket 失败", it) }
        }

        return failed
    }

    /** appops 全量写回：逐条比对，不一样的才写，备份里没有的设回默认。 */
    private fun applyAppOps(json: JSONObject?, packageName: String, uid: Int) {
        if (json == null) return

        fun modes(section: JSONObject?): Map<Int, Int> {
            if (section == null) return emptyMap()
            val result = HashMap<Int, Int>()
            for (key in section.keys()) {
                val op = key.toIntOrNull() ?: continue
                result[op] = section.optInt(key, AppOpsManager.MODE_DEFAULT)
            }
            return result
        }

        val wantPackage = modes(json.optJSONObject("package"))
        val wantUid = modes(json.optJSONObject("uid"))
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
        for (op in opCodes) {
            val name = op.toString()

            val packageTarget = wantPackage[op] ?: AppOpsManager.MODE_DEFAULT
            if ((currentPackage[op] ?: AppOpsManager.MODE_DEFAULT) != packageTarget) {
                runCatching { appOpsManager.setMode(op, uid, packageName, packageTarget) }
                    .onFailure { Log.w(TAG, "setMode $name=$packageTarget 失败", it) }
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
                    .onFailure { Log.w(TAG, "setUidMode $name=$uidTarget 失败", it) }
                    .onSuccess { written++ }
            }
        }
        Log.d(
            TAG,
            "appops $packageName uid=$uid 写入 $written 条 | " +
                    "目标 pkg=${wantPackage.size} uid=${wantUid.size} | " +
                    "当前 pkg=${currentPackage.size} uid=${currentUid.size}"
        )
    }

    private fun JSONArray?.toStringSet(): Set<String> {
        if (this == null) return emptySet()
        return (0 until length())
            .map { optString(it) }
            .filter { it.isNotBlank() }
            .toSet()
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

/** 用 `pm install` 把快照里的 APK 装到指定用户；失败时带上 pm 的输出。 */
internal fun installApk(apk: File, userId: Int): Result<Int> {
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

/**
 * 恢复后修正归属和 SELinux 上下文。
 *
 * @param parentContext 用**父目录**的上下文而不是自己的。
 *   外部存储（/data/media/0/...）必须传 true —— 那里的文件不该带 MLS 类别，
 *   带上了就只有「类别相同」的那个应用能访问，别的应用连删除都做不到。
 *   应用数据（/data/user/0/<pkg>）要保持 false，它的类别是按 uid 算的，必须有。
 */
internal fun fixupTree(file: File, uid: Int, gid: Int, parentContext: Boolean = false) {
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
    val script = "ctx=\$(ls -Zd \"$ctxSource\" | grep -o 'u:object_r:[^ ]*'); " +
            "chown -R $uid:$gid \"$path\"; " +
            chcon

    val result = runCatching { Shell.cmd(script).exec() }.getOrNull()
    if (result == null || !result.isSuccess) {
        // 归属改不过去应用会读不到自己的数据，必须让用户看见
        val detail = result?.err?.joinToString(" ").orEmpty()
            .ifBlank { "退出码 ${result?.code ?: -1}" }
        Log.w(TAG, "fixup failed: $path ($detail)")
        ErrorLog.add("恢复", "修改文件归属失败：$path（$detail）", IllegalStateException(detail))
    }
}
