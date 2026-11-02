package com.cc.libackupnext.service.util

import android.content.Context
import android.graphics.Bitmap
import android.os.UserManagerHidden
import androidx.core.graphics.drawable.toBitmap
import com.cc.libackupnext.data.BackupTarget
import com.cc.libackupnext.data.SnapshotKind
import dev.rikka.tools.refine.Refine
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 把备份请求（options）翻译成 rustic 的请求 JSON。
 *
 * 顺便把要进快照的源文件写好：图标、权限 / appops / 白名单、apk 路径。
 */

/** 外部存储路径换成 /data/media 视角（root 进程里读得到）。 */
internal fun mediaPath(path: String): String = when {
    path == "/storage/emulated/0" -> "/data/media/0"
    path.startsWith("/storage/emulated/0/") ->
        "/data/media/0/" + path.removePrefix("/storage/emulated/0/")

    else -> path
}

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
 * 缓存、原生库、OTA 临时文件占空间大又没有恢复价值。
 */
private val BACKUP_EXCLUDES: List<String> = buildList {
    BACKUP_ROOTS.forEach { root ->
        BACKUP_EXCLUDED_NAMES.forEach { name -> add("!$root/$name") }
    }
}

/**
 * apk的过滤规则，只保留指定文件夹的下的apk文件，其他的全都过滤，包括子文件夹
 */
private val APK_EXCLUDES = listOf("**/*.apk", "!*/", "!.*/")

internal class RusticRequestBuilder(
    private val context: Context,
    private val appState: AppState
) {


    fun build(options: JSONObject): String? {
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
        // 目录备份：一个目录就是一个快照，label 固定为 `dir:<绝对路径>`。
        // 快照里顶层目录名用文件夹名（as_path），恢复时落到父目录即可还原。
        if (packageName.startsWith('/')) {
            val dir = File(packageName)
            val dirItems = JSONArray().put(
                item(
                    SnapshotKind.DIR.key,
                    "$packageName:${SnapshotKind.DIR.key}",
                    tags,
                    // 备份走真实路径：/sdcard 是 FUSE 视图，root 读写会被权限限制
                    listOf(mediaPath(packageName)),
                    dir.name,
                    if (dir.isDirectory) null else "目录不存在",
                    descriptionOf(SnapshotKind.DIR.key)
                )
            )
            return JSONObject().apply {
                put("path", options.getString("repoPath"))
                put("password", options.getString("repoPassword"))
                put("items", dirItems)
                // 不备份.thumbnails 文件夹和 .trashed开头的文件
                put(
                    "excludes",
                    JSONArray().put("!.thumbnails").put("!.trashed*")
                )
                options.optString("batch").takeIf { it.isNotBlank() }?.let { put("batch", it) }
            }.toString()
        }

        // 系统数据（Wi-Fi / 联系人 / 通话记录）：内容由 root 侧导出成 JSON。
        // 这里只声明要备份哪一项，sources 留空、由 RusticServer 导出后填。
        val fixedTarget = BackupTarget.entries
            .firstOrNull { it.fixedId != null && it.fixedId == packageName }
        if (fixedTarget != null) {
            val kind = fixedTarget.kinds.first().kinds.first()

            val systemItem = item(
                kind.key,
                labelOf(kind.key, "$packageName:${kind.key}"),
                tagsOf(kind.key, tags),
                sources = emptyList(),
                asPath = "$packageName.json",
                description = descriptionOf(kind.key)
            ).put("system", true)

            parents?.optString(kind.key)?.takeIf { it.isNotBlank() }?.let {
                systemItem.put("parent", it)
            }

            return JSONObject().apply {
                put("path", options.getString("repoPath"))
                put("password", options.getString("repoPassword"))
                put("items", JSONArray().put(systemItem))
                options.optString("batch").takeIf { it.isNotBlank() }?.let { put("batch", it) }
            }.toString()
        }

        val items = JSONArray()

        // 图标：仓库里已经有同 label 的快照就不再备份（策略在 service 层判断）
        val includeIcon = options.optBoolean("includeIcon", true)
        if (includeIcon) writeIcon(packageName)?.let { iconPath ->
            items.put(
                item(
                    SnapshotKind.ICON.key,
                    "$packageName:${SnapshotKind.ICON.key}",
                    listOf(packageName),
                    listOf(iconPath),
                    asPath = File(iconPath).name
                )
            )
        }

        // 即使某个来源暂时不存在也要带上，Rust 侧会回报「跳过」
        if (options.optBoolean(SnapshotKind.APK.key, false)) {
            val installDir = apkPaths(packageName)
                .firstOrNull()
                ?.let { File(it).parentFile?.absolutePath }
            items.put(
                item(
                    SnapshotKind.APK.key,
                    labelOf(SnapshotKind.APK.key, "$packageName:${SnapshotKind.APK.key}"),
                    tagsOf(SnapshotKind.APK.key, tags),
                    listOfNotNull(installDir),
                    asPath = "/",
                    skipReason = if (installDir == null) "找不到 APK" else null,
                    description = descriptionOf(SnapshotKind.APK.key),
                    excludes = APK_EXCLUDES
                )
            )
        }

        if (options.optBoolean(SnapshotKind.OBB.key, false)) {
            val obbPath = "/data/media/$userId/Android/obb/$packageName"
            items.put(
                item(
                    SnapshotKind.OBB.key,
                    labelOf(SnapshotKind.OBB.key, "$packageName:${SnapshotKind.OBB.key}"),
                    tagsOf(SnapshotKind.OBB.key, tags),
                    listOf(obbPath),
                    asPath = packageName,
                    skipReason = skipReasonOf(obbPath),
                    description = descriptionOf(SnapshotKind.OBB.key)
                )
            )
        }

        // 已授予的运行时权限：写成 permissions.json 一起进仓库。
        if (options.optBoolean(SnapshotKind.PERMISSIONS.key, false)) {
            val permissionFile = appState.write(packageName, userId)
            items.put(
                item(
                    SnapshotKind.PERMISSIONS.key,
                    labelOf(
                        SnapshotKind.PERMISSIONS.key,
                        "$packageName:${SnapshotKind.PERMISSIONS.key}"
                    ),
                    tagsOf(SnapshotKind.PERMISSIONS.key, tags),
                    listOfNotNull(permissionFile),
                    asPath = "permissions.json",
                    skipReason = if (permissionFile == null) "没有已授予的运行时权限" else null,
                    description = descriptionOf(SnapshotKind.PERMISSIONS.key)
                )
            )
        }

        if (options.optBoolean(SnapshotKind.USER.key, false)) {
            val userDataPath = "/data/user/$userId/$packageName"
            val userDePath = "/data/user_de/$userId/$packageName"

            items.put(
                item(
                    SnapshotKind.USER.key,
                    labelOf(SnapshotKind.USER.key, "$packageName:${SnapshotKind.USER.key}"),
                    tagsOf(SnapshotKind.USER.key, tags),
                    listOf(userDataPath),
                    asPath = packageName,
                    skipReason = skipReasonOf(userDataPath),
                    description = descriptionOf(SnapshotKind.USER.key)
                )
            )
            items.put(
                item(
                    SnapshotKind.USER_DE.key,
                    labelOf(SnapshotKind.USER_DE.key, "$packageName:${SnapshotKind.USER_DE.key}"),
                    tagsOf(SnapshotKind.USER_DE.key, tags),
                    listOf(userDePath),
                    asPath = packageName,
                    skipReason = skipReasonOf(userDePath),
                    description = descriptionOf(SnapshotKind.USER_DE.key)
                )
            )
        }

        if (options.optBoolean(SnapshotKind.DATA.key, false)) {
            val dataPath = "/data/media/$userId/Android/data/$packageName"
            items.put(
                item(
                    SnapshotKind.DATA.key,
                    labelOf(SnapshotKind.DATA.key, "$packageName:${SnapshotKind.DATA.key}"),
                    tagsOf(SnapshotKind.DATA.key, tags),
                    listOf(dataPath),
                    asPath = packageName,
                    skipReason = skipReasonOf(dataPath),
                    description = descriptionOf(SnapshotKind.DATA.key)
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
        description: String? = null,
        excludes: List<String>? = null
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
        if (excludes != null) {
            put("excludes", JSONArray(excludes))
        }
    }


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
