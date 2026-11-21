package com.cc.libackupnext.service.root

import android.content.Context
import android.graphics.Bitmap
import android.os.UserManagerHidden
import androidx.core.graphics.drawable.toBitmap
import com.cc.libackupnext.data.BackupTarget
import com.cc.libackupnext.data.SnapshotKind
import com.cc.libackupnext.data.json.BackupItemDto
import com.cc.libackupnext.data.json.BackupOptions
import com.cc.libackupnext.data.json.RusticBackupRequest
import dev.rikka.tools.refine.Refine
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 把备份请求（options）翻译成 rustic 的请求结构。
 *
 * 顺便把要进快照的源文件写好：图标、权限 / appops / 白名单、apk 路径。
 * 真正序列化成 JSON 交给 Rust 的动作在 [com.cc.libackupnext.service.RusticServer]。
 */

/** 外部存储路径换成 /data/media 视角（root 进程里读得到）。 */
internal fun mediaPath(path: String): String = when {
    path == "/storage/emulated/0" -> "/data/media/0"
    path.startsWith("/storage/emulated/0/") ->
        "/data/media/0/" + path.removePrefix("/storage/emulated/0/")

    else -> path
}

/**
 * 这批源要不要关掉 inode 比较。
 *
 * 例外是 FUSE 视图：SD 卡、非主用户这些路径的 inode 编号由用户态分配，重挂载后
 * 可能整体变一遍，全量重读没有任何收益。对它们关掉比较也不损失正确性 ——
 * 真改过内容时 ctime 一定会变，靠 ctime 仍然能发现。
 */
internal fun ignoreInodeFor(sources: List<String>): Boolean =
    sources.isNotEmpty() && sources.all { !it.startsWith("/data/") }

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

    fun build(options: BackupOptions): RusticBackupRequest? {
        val packageName = options.packageName
        val userId = options.userId
        val timestamp = options.timestamp.takeIf { it > 0 } ?: System.currentTimeMillis()
        val userName = userNameOf(userId)

        val appName = options.appName.ifBlank { packageName }
        val version = options.version
        val overrides = options.overrides

        // 类型 -> 上一次同类快照的 id，作为 rustic 的 parent 做增量备份
        val parents = options.parents

        // 拼成一个 tag：应用名|版本|用户名|时间戳
        // rustic 的 tags 是集合会排序，拆成多个就丢顺序了
        val tags = listOf(
            listOf(appName.replace("|", "/"), version, userName, timestamp.toString())
                .joinToString("|")
        )

        // 覆盖备份时沿用旧快照的 label / tag / 备注
        fun labelOf(kind: String, fallback: String): String =
            overrides?.get(kind)?.label?.takeIf { it.isNotBlank() } ?: fallback

        fun tagsOf(kind: String, fallback: List<String>): List<String> {
            val raw = overrides?.get(kind)?.tags.orEmpty()
            if (raw.isBlank()) return fallback
            return raw.split(',').filter { it.isNotBlank() }
        }

        fun descriptionOf(kind: String): String? =
            overrides?.get(kind)?.description?.takeIf { it.isNotBlank() }

        val batch = options.batch?.takeIf { it.isNotBlank() }

        // 目录备份：一个目录就是一个快照，label 是 `<绝对路径>:dir` —— 类型仍在最后，
        // 和应用 / 固定目标同构，SnapshotMeta 一套规则就能解析全部 label。
        // 快照里顶层目录名用文件夹名（as_path），恢复时落到父目录即可还原。
        if (packageName.startsWith('/')) {
            val dir = File(packageName)
            val dirItem = item(
                stage = SnapshotKind.DIR.key,
                label = "$packageName:${SnapshotKind.DIR.key}",
                tags = tags,
                // 备份走真实路径：/sdcard 是 FUSE 视图，root 读写会被权限限制
                sources = listOf(mediaPath(packageName)),
                asPath = dir.name,
                skipReason = if (dir.isDirectory) null else "目录不存在",
                description = descriptionOf(SnapshotKind.DIR.key)
            )
            return RusticBackupRequest(
                items = listOf(dirItem),
                // 不备份.thumbnails 文件夹和 .trashed开头的文件
                excludes = listOf("!.thumbnails", "!.trashed*"),
                batch = batch
            )
        }

        // 系统数据（Wi-Fi / 联系人 / 通话记录 / 短信）：内容由 root 侧导出成 JSON。
        // 这里只声明要备份哪几项，sources 留空、由 RusticServer 导出后填。
        val fixedTarget = BackupTarget.entries
            .firstOrNull { it.fixedId != null && it.fixedId == packageName }
        if (fixedTarget != null) {
            // 这个目标支持的全部类型；用户勾了哪些就只备哪些（没传 kinds 时按全备处理）
            val available = fixedTarget.kinds.flatMap { it.kinds }
            val wanted = options.kinds
                ?.mapNotNull { SnapshotKind.of(it) }
                ?.filter { it in available }
                ?: available
            if (wanted.isEmpty()) return null

            val items = wanted.map { kind ->
                item(
                    stage = kind.key,
                    label = labelOf(kind.key, "$packageName:${kind.key}"),
                    tags = tagsOf(kind.key, tags),
                    sources = emptyList(),
                    // 兜底值：RusticServer 把内容导出成目录后，会把 as_path 覆盖成 "/"，
                    // 让整个导出目录就是快照根
                    asPath = "${kind.key}.json",
                    description = descriptionOf(kind.key)
                ).copy(
                    system = true,
                    parent = parents?.get(kind.key)?.takeIf { it.isNotBlank() }
                )
            }

            return RusticBackupRequest(items = items, batch = batch)
        }

        val items = mutableListOf<BackupItemDto>()

        // 图标：仓库里已经有同 label 的快照就不再备份（策略在 service 层判断）
        if (options.includeIcon) writeIcon(packageName)?.let { iconPath ->
            items.add(
                item(
                    stage = SnapshotKind.ICON.key,
                    label = "$packageName:${SnapshotKind.ICON.key}",
                    tags = listOf(packageName),
                    sources = listOf(iconPath),
                    asPath = File(iconPath).name
                )
            )
        }

        // 即使某个来源暂时不存在也要带上，Rust 侧会回报「跳过」
        if (options.apk) {
            val installDir = apkPaths(packageName)
                .firstOrNull()
                ?.let { File(it).parentFile?.absolutePath }
            items.add(
                item(
                    stage = SnapshotKind.APK.key,
                    label = labelOf(SnapshotKind.APK.key, "$packageName:${SnapshotKind.APK.key}"),
                    tags = tagsOf(SnapshotKind.APK.key, tags),
                    sources = listOfNotNull(installDir),
                    asPath = "/",
                    skipReason = if (installDir == null) "找不到 APK" else null,
                    description = descriptionOf(SnapshotKind.APK.key),
                    excludes = APK_EXCLUDES
                )
            )
        }

        if (options.obb) {
            val obbPath = "/data/media/$userId/Android/obb/$packageName"
            items.add(
                item(
                    stage = SnapshotKind.OBB.key,
                    label = labelOf(SnapshotKind.OBB.key, "$packageName:${SnapshotKind.OBB.key}"),
                    tags = tagsOf(SnapshotKind.OBB.key, tags),
                    sources = listOf(obbPath),
                    asPath = packageName,
                    skipReason = skipReasonOf(obbPath),
                    description = descriptionOf(SnapshotKind.OBB.key)
                )
            )
        }

        // 已授予的运行时权限：写成 permissions.json 一起进仓库。
        if (options.permissions) {
            val permissionFile = appState.write(packageName, userId)
            items.add(
                item(
                    stage = SnapshotKind.PERMISSIONS.key,
                    label = labelOf(
                        SnapshotKind.PERMISSIONS.key,
                        "$packageName:${SnapshotKind.PERMISSIONS.key}"
                    ),
                    tags = tagsOf(SnapshotKind.PERMISSIONS.key, tags),
                    sources = listOfNotNull(permissionFile),
                    asPath = "permissions.json",
                    skipReason = if (permissionFile == null) "没有已授予的运行时权限" else null,
                    description = descriptionOf(SnapshotKind.PERMISSIONS.key)
                )
            )
        }

        if (options.user) {
            val userDataPath = "/data/user/$userId/$packageName"
            val userDePath = "/data/user_de/$userId/$packageName"

            items.add(
                item(
                    stage = SnapshotKind.USER.key,
                    label = labelOf(SnapshotKind.USER.key, "$packageName:${SnapshotKind.USER.key}"),
                    tags = tagsOf(SnapshotKind.USER.key, tags),
                    sources = listOf(userDataPath),
                    asPath = packageName,
                    skipReason = skipReasonOf(userDataPath),
                    description = descriptionOf(SnapshotKind.USER.key)
                )
            )
            items.add(
                item(
                    stage = SnapshotKind.USER_DE.key,
                    label = labelOf(
                        SnapshotKind.USER_DE.key,
                        "$packageName:${SnapshotKind.USER_DE.key}"
                    ),
                    tags = tagsOf(SnapshotKind.USER_DE.key, tags),
                    sources = listOf(userDePath),
                    asPath = packageName,
                    skipReason = skipReasonOf(userDePath),
                    description = descriptionOf(SnapshotKind.USER_DE.key)
                )
            )
        }

        if (options.data) {
            val dataPath = "/data/media/$userId/Android/data/$packageName"
            items.add(
                item(
                    stage = SnapshotKind.DATA.key,
                    label = labelOf(SnapshotKind.DATA.key, "$packageName:${SnapshotKind.DATA.key}"),
                    tags = tagsOf(SnapshotKind.DATA.key, tags),
                    sources = listOf(dataPath),
                    asPath = packageName,
                    skipReason = skipReasonOf(dataPath),
                    description = descriptionOf(SnapshotKind.DATA.key)
                )
            )
        }

        if (items.isEmpty()) return null

        // 把 parent 挂到对应类型的 item 上：rustic 有了显式 parent 就不用
        // 「列一遍仓库里所有快照找最新」了（那是每个类型一次的全量扫描）
        val withParents = parents?.let { map ->
            items.map { item ->
                map[item.stage]?.takeIf { it.isNotBlank() }?.let { parent ->
                    item.copy(parent = parent)
                } ?: item
            }
        } ?: items

        return RusticBackupRequest(
            items = withParents,
            excludes = BACKUP_EXCLUDES,
            batch = batch
        )
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
        excludes: List<String>? = null,
        // 默认按源的路径可信度决定，见 [ignoreInodeFor]
        ignoreInode: Boolean = ignoreInodeFor(sources)
    ): BackupItemDto = BackupItemDto(
        stage = stage,
        label = label,
        tags = tags,
        sources = sources,
        asPath = asPath,
        skipReason = skipReason,
        description = description,
        excludes = excludes.orEmpty(),
        ignoreInode = ignoreInode
    )

    /**
     * 把应用图标导出成 PNG，作为 icon 快照的源文件。
     *
     * 每次都在同一个路径上原地重写（inode 不变），并把 mtime 归零，所以只要图标
     * 内容没变，size / mtime / inode 三项都对得上。ctime 每次写都会变（内核维护，
     * 设不了），rustic 会因此重读这一个小文件 —— 代价可以忽略；而且「要不要再建
     * 一个 icon 快照」由调用方的 includeIcon 决定，本来也不靠这里的跳过。
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
