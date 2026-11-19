package com.cc.libackupnext.service.browse

import android.os.Environment
import android.os.storage.StorageManager
import com.cc.libackupnext.App
import com.cc.libackupnext.data.LocalVolume
import com.cc.libackupnext.data.PathSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 本地卷元数据：文件夹选择器第一屏与路径面包屑的数据来源。
 */
object LocalVolumes {

    /** 本地目录选择的默认起点：内部存储根目录。 */
    val localBrowseRoot: String
        get() = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')

    /** 卷分类与展示顺序；「根目录」不在这里，allowRoot 时单独插到内部存储之后。 */
    private enum class VolumeKind(val label: String, val order: Int) {
        INTERNAL("内部存储", 0),
        SD_CARD("SD卡", 2),
        EXTERNAL("外置存储", 3)
    }

    /** 共享挂载统一落在 /mnt 下；这里只认这个通用挂载域，不列举任何具体路径。 */
    private const val SHARE_PREFIX = "/mnt/"
    private const val MOUNTS_FILE = "/proc/mounts"

    /**
     * 第一屏入口：主卷是「内部存储」，已挂载的可移除卷按系统描述分成
     * 「SD卡」/「外置存储」，/mnt 下的共享挂载按挂载表补到最后，allowRoot 时在
     * 内部存储之后补「根目录」("/")。未挂载或拿不到目录的卷跳过。
     */
    suspend fun list(allowRoot: Boolean): List<LocalVolume> =
        withContext(Dispatchers.IO) { read(allowRoot, true) }

    /** 系统卷枚举：只认已挂载、能拿到挂载点的卷，别把其它用户的模拟卷列出来。 */
    private fun systemVolumes(): List<Pair<LocalVolume, VolumeKind>> = runCatching {
        val context = App.application
        val manager = context.getSystemService(StorageManager::class.java)
        manager?.getStorageVolumes().orEmpty().mapNotNull { vol ->
            val path = runCatching { vol.getDirectory()?.absolutePath?.trimEnd('/') }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val state = runCatching { vol.getState() }.getOrNull()
            if (state != Environment.MEDIA_MOUNTED &&
                state != Environment.MEDIA_MOUNTED_READ_ONLY
            ) return@mapNotNull null
            val description = runCatching { vol.getDescription(context) }
                .getOrDefault("").orEmpty()
            val removable = runCatching { vol.isRemovable() }.getOrDefault(false)
            val primary = runCatching { vol.isPrimary() }.getOrDefault(false)
            // 工作资料等其它用户的模拟卷不属于当前用户，别当成外置存储列出来
            if (!primary && runCatching { vol.isEmulated() }.getOrDefault(false)) {
                return@mapNotNull null
            }
            // 主卷之外的 /mnt 挂载是厂商 / 模拟器共享目录，直接用系统描述当卷名
            val share = !primary && path.startsWith(SHARE_PREFIX)
            val kind = when {
                share -> VolumeKind.EXTERNAL
                primary -> VolumeKind.INTERNAL
                // 采用内置存储的第二个卷（如内部化后残留的逻辑卷）算外置
                !removable -> VolumeKind.EXTERNAL
                // 系统描述里带 USB / OTG / U 盘的都是移动硬盘、U 盘一类
                description.lowercase().let {
                    it.contains("usb") || it.contains("otg") ||
                            it.contains("u盘") || it.contains("u 盘")
                } -> VolumeKind.EXTERNAL
                else -> VolumeKind.SD_CARD
            }
            val label = if (share && description.isNotBlank()) description else kind.label
            LocalVolume(
                path = path,
                label = label,
                readOnly = state == Environment.MEDIA_MOUNTED_READ_ONLY
            ) to kind
        }
    }.getOrDefault(emptyList())

    /**
     * 共享挂载从 /proc/mounts 动态发现：路径和数量都由系统给出，不维护清单。
     * 只保留真实可列的目录，并排掉指向系统卷的软链与同一卷的另一个入口，
     * 空目录仍允许进入，免得刚挂载还没放文件时第一屏看不到它。
     */
    private fun discoverShares(knownPaths: List<String>): List<LocalVolume> = runCatching {
        File(MOUNTS_FILE).readLines().mapNotNull { line ->
            val mountPoint = line.split(' ').getOrNull(1)
                ?.replace("\\040", " ")
                ?.trimEnd('/')
                ?.takeIf { it.startsWith(SHARE_PREFIX) } ?: return@mapNotNull null
            val dir = File(mountPoint)
            if (!dir.isDirectory) return@mapNotNull null
            // canonicalPath 会解析软链：指向 /storage 的入口不算共享挂载
            val canonical = runCatching { dir.canonicalPath }
                .getOrDefault(mountPoint)
                .trimEnd('/')
            if (!canonical.startsWith(SHARE_PREFIX)) return@mapNotNull null
            // 读不到内容（权限 / 系统内部挂载）才跳过；空数组说明目录可浏览
            if (runCatching { dir.listFiles() }.getOrNull() == null) return@mapNotNull null
            val name = File(canonical).name
            val duplicated = knownPaths.any { known ->
                canonical == known || canonical.startsWith("$known/") ||
                        File(known).name.equals(name, ignoreCase = true)
            }
            if (duplicated) return@mapNotNull null
            LocalVolume(canonical, name)
        }.distinctBy { it.path }
    }.getOrDefault(emptyList())

    /**
     * 组装第一屏 / 路径别名用的卷表。
     *
     * [includeShares] 只给展示用；修正新建目录归属时传 false，避免把共享目录
     * 当成 FUSE 外部存储去 chown，那里未必是 media_rw 的语义。
     */
    internal fun read(allowRoot: Boolean, includeShares: Boolean): List<LocalVolume> {
        val volumes = systemVolumes()
            .distinctBy { it.first.path }
            .sortedWith(
                compareBy<Pair<LocalVolume, VolumeKind>> { it.second.order }
                    .thenBy { it.first.path }
            )
            .map { it.first }
            .toMutableList()
        // 系统没报主卷时补一个，保证第一屏永远能进内部存储
        if (volumes.none { it.label == VolumeKind.INTERNAL.label }) {
            volumes.add(0, LocalVolume(localBrowseRoot, VolumeKind.INTERNAL.label))
        }
        if (allowRoot) {
            val internalIndex = volumes.indexOfFirst { it.label == VolumeKind.INTERNAL.label }
            volumes.add(internalIndex + 1, LocalVolume("/", "根目录"))
        }
        if (includeShares) {
            discoverShares(volumes.map { it.path })
                .sortedBy { it.path }
                .forEach { volumes.add(it) }
        }
        return volumes
    }

    /**
     * 把本地绝对路径拆成可点的面包屑，每段自带要跳转的绝对目录，UI 只负责渲染。
     *
     * 显示名一律回查卷表：allowRoot=false 卷根整段换成卷名（内部存储 / 系统描述），
     * 只显示卷内相对层级；allowRoot=true 显示完整绝对路径，但某一段正好是卷根时
     * 也换成该卷名。点第一段（卷名）回到该卷根目录，回卷列表页走「..」。
     */
    fun pathSegments(path: String, allowRoot: Boolean): List<PathSegment> {
        val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
        val volumes = read(false, true).filter { it.path != "/" }
        val matched = if (allowRoot) null else {
            volumes
                .filter { path == it.path || path.startsWith("${it.path.trimEnd('/')}/") }
                .maxByOrNull { it.path.length }
        }
        return buildList {
            if (matched != null) {
                add(PathSegment(matched.label, matched.path))
                var current = matched.path.trimEnd('/')
                path.removePrefix(matched.path).trim('/')
                    .split('/')
                    .filter { it.isNotEmpty() }
                    .forEach { segment ->
                        current = "$current/$segment"
                        add(PathSegment(segment, current))
                    }
            } else {
                add(PathSegment("根目录", "/"))
                var current = ""
                parts.forEach { segment ->
                    current = "$current/$segment"
                    val label = volumes.firstOrNull {
                        it.path.equals(current, ignoreCase = true)
                    }?.label
                    add(PathSegment(label ?: segment, current))
                }
            }
        }
    }
}
