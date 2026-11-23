package com.cc.libackupnext.service.media

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Process
import android.os.SystemClock
import com.cc.libackupnext.data.MediaPath
import com.cc.libackupnext.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 恢复完文件夹之后，把里面的媒体文件重新登记进 MediaStore。
 * 防止小米系统在播放视频时提示视频无法播放。
 */
interface MediaRescanner {

    /**
     * 扫描 [dir] 下的媒体文件。
     *
     * @param dir **FUSE 视角**的绝对路径（`/storage/emulated/0/DCIM`），不是物理路径 ——
     *   MediaStore 只认卷内路径，传 `/data/media/0/…` 会被判成「不在任何卷里」。
     * @param onProgress 已处理 / 总数（都是文件数）。遍历出文件列表后立刻回调一次
     *   `(0, total)`，之后每扫完一批回调一次。
     */
    suspend fun rescan(
        dir: String,
        onProgress: ((processed: Int, total: Int) -> Unit)? = null
    ): RescanResult
}

/**
 * 列出某个**物理目录**下的所有文件（返回绝对物理路径）。失败返回 null。
 *
 * 存在的理由：恢复是 root 直接写 `/data/media` 的，MediaProvider 的 FUSE 视图有自己的
 * 目录缓存，刚写完时主进程 `listFiles()` 可能只返回恢复前的内容（实测：Camera 下
 * 只剩一个空的 `Raw/`，而照片其实都在）。root 读物理路径不受这层缓存影响。
 */
interface MediaFileLister {

    suspend fun list(physicalDir: String): List<String>?
}

/**
 * 一次重建的结果。
 *
 * @param indexed 成功进索引的文件数
 * @param total 找到的媒体文件总数
 * @param truncated 超过 [AndroidMediaRescanner.MAX_FILES] 上限，后面的没扫
 * @param timedOut 超出总时间预算，提前收手
 * @param skipped 目标目录不在当前用户空间，根本没扫
 */
data class RescanResult(
    val indexed: Int,
    val total: Int,
    val truncated: Boolean,
    val timedOut: Boolean,
    val skipped: Boolean = false
) {

    /** 给用户看的一句话，挂在进度窗口的这一步下面。 */
    val text: String
        get() = when {
            skipped -> "目录不在当前用户空间，跳过索引重建"
            timedOut -> "相册索引未完全更新（已处理 $indexed / $total 个文件）"
            truncated -> "已更新相册索引（$indexed 个文件，目录文件过多，只处理了前 $total 个）"
            // 找到文件但一个都没登记上，和「本来就没有媒体文件」是两回事，
            // 混成一句话会让真正的失败看起来像正常
            total == 0 -> "没有需要索引的媒体文件"
            indexed == 0 -> "相册索引更新失败（$total 个文件未能登记）"
            else -> "已更新相册索引（$indexed 个文件）"
        }
}

/** [MediaRescanner] 的默认实现：递归列媒体文件，分批交给 MediaScanner。 */
internal class AndroidMediaRescanner(
    private val context: Context,
    private val lister: MediaFileLister,
) : MediaRescanner {

    override suspend fun rescan(
        dir: String,
        onProgress: ((processed: Int, total: Int) -> Unit)?
    ): RescanResult = withContext(Dispatchers.IO) {
        // 跨用户的目录（分身 / 工作资料）主进程看不到：MANAGE_EXTERNAL_STORAGE 只覆盖
        // 自己那个用户空间。这种目录只能靠那个用户里的 MediaProvider 自己扫。
        // SD 卡这类不归属用户的路径（userOf 返回 null）不受影响。
        val dirUser = MediaPath.userOf(dir)
        if (dirUser != null && dirUser != currentUserId()) {
            return@withContext RescanResult(0, 0, false, false, skipped = true)
        }

        // 直接走 root 列物理目录，不走 FUSE。
        //
        // 恢复是 root 直接写 /data/media 的，MediaProvider 的 FUSE 目录缓存还停在恢复前
        // （实测：Camera 下只剩一个空的 Raw/，照片其实都在）。主进程 listFiles() 看到的是
        // 旧内容，而 root 读物理路径没有这层缓存 —— 代价只是一次 shell 调用。
        val collected = collectViaRoot(dir)

        val files = collected.files
        AppLog.i(
            TAG,
            "媒体索引：$dir 找到 ${files.size} 个媒体文件（截断=${collected.truncated}）"
        )
        if (files.isEmpty()) {
            return@withContext RescanResult(0, 0, collected.truncated, false)
        }

        onProgress?.invoke(0, files.size)

        var indexed = 0
        var offset = 0
        var timedOut = false
        val deadline = SystemClock.elapsedRealtime() + TOTAL_BUDGET_MS

        while (offset < files.size) {
            // 总预算到了就收手：宁可少索引几个，也不要让恢复流程挂在后台
            if (SystemClock.elapsedRealtime() > deadline) {
                timedOut = true
                break
            }
            val end = minOf(offset + BATCH, files.size)
            val paths = files.subList(offset, end).toTypedArray()
            val batchIndexed = scanBatch(paths)
            if (batchIndexed < paths.size) {
                AppLog.w(TAG, "本批 ${paths.size} 个文件只登记了 $batchIndexed 个")
            }
            indexed += batchIndexed
            offset = end
            onProgress?.invoke(offset, files.size)
        }

        AppLog.i(TAG, "媒体索引：$dir 登记 $indexed/${files.size}")
        RescanResult(indexed, files.size, collected.truncated, timedOut)
    }

    private class Collected(val files: List<String>, val truncated: Boolean)

    /**
     * 让 root 直接列物理目录，再把物理路径翻译回 FUSE 路径。
     *
     * MediaProvider 收到 FUSE 路径后是自己去 `/data/media` 读文件的，不需要主进程
     * 在那个路径上「看得见」—— 所以这里只借 root 的眼睛拿路径，扫描仍由主进程发起。
     */
    private suspend fun collectViaRoot(dir: String): Collected {
        val physical = MediaPath.physical(dir)
        // root 是硬前提：没有 root 这个应用连仓库都打不开，走不到恢复这一步。
        // 所以这里拿不到列表只可能是目录不存在或 find 出错 —— 当成「没有文件」即可。
        val all = lister.list(physical) ?: return Collected(emptyList(), false)

        val base = physical.trimEnd('/')
        // Android/data、Android/obb 是应用私有数据，MediaStore 本来就不收；
        // 真扫进去只会让相册里冒出应用的缓存图
        val androidPrefix = "$base/$ANDROID_DIR/"
        // .nomedia 的语义要保留：它所在目录整棵子树都不进索引
        val noMediaDirs = all
            .filter { it.substringAfterLast('/') == NOMEDIA }
            .map { it.substringBeforeLast('/') }

        val media = all.filter { path ->
            isMedia(path) &&
                    !path.startsWith(androidPrefix) &&
                    noMediaDirs.none { dirPath -> path.startsWith("$dirPath/") }
        }

        val fusePrefix = dir.trimEnd('/')
        val fuse = media.map { path ->
            if (path.startsWith(base)) fusePrefix + path.removePrefix(base) else path
        }

        AppLog.i(TAG, "媒体索引：root 列出 ${fuse.size} 个媒体文件（物理 $physical）")
        return if (fuse.size > MAX_FILES) {
            Collected(fuse.take(MAX_FILES), true)
        } else {
            Collected(fuse, false)
        }
    }

    private fun isMedia(path: String): Boolean =
        path.substringAfterLast('/').substringAfterLast('.', "").lowercase() in MEDIA_EXTENSIONS

    /**
     * 一批文件交给 MediaScanner，等它们全部回调再返回。
     *
     * 必须等：用户看到「恢复成功」之后第一件事就是去相册确认，
     * 这时候索引还没建好，等于没做。
     *
     * @return 成功入索引的数量；超时返回 0（宁可少报，也不要卡住恢复流程）
     */
    private suspend fun scanBatch(paths: Array<String>): Int =
        withTimeoutOrNull(BATCH_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                var finished = 0
                var indexed = 0
                MediaScannerConnection.scanFile(context, paths, null) { _, uri ->
                    finished++
                    if (uri != null) indexed++
                    // 超时之后 continuation 已经取消，再 resume 会抛异常
                    if (finished == paths.size && cont.isActive) cont.resume(indexed)
                }
            }
        } ?: 0

    private companion object {
        const val TAG = "MediaRescan"
        const val NOMEDIA = ".nomedia"
        const val ANDROID_DIR = "Android"

        /**
         * 当前进程所在的用户空间。
         *
         * uid 的编码是 `userId * 100000 + appId`，除法是取 userId 最直接的办法；
         * `Context.getUserId()` 在这个 SDK 上不可见。
         */
        fun currentUserId(): Int = Process.myUid() / 100_000

        /** 一次交给 MediaScanner 的路径数：500 个约 30KB，远低于 binder 的 1MB 上限。 */
        const val BATCH = 500

        /** 单批的等待上限。MediaScanner 服务起不来时别干等 30 秒。 */
        const val BATCH_TIMEOUT_MS = 5_000L

        /** 整个目录的总预算。5 万文件 ÷ 500 × 0.5 秒 ≈ 50 秒，留一点余量。 */
        const val TOTAL_BUDGET_MS = 60_000L

        /** 单个目录最多扫这么多文件，防止用户误把整个 /storage 当备份目标时扫到天荒地老。 */
        const val MAX_FILES = 50_000

        /** 会进 MediaStore 的扩展名。不在表里的一律不扫。 */
        val MEDIA_EXTENSIONS = setOf(
            // 图片
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "dng",
            // 视频
            "mp4", "mkv", "avi", "mov", "3gp", "3g2", "webm", "m4v", "ts", "flv", "wmv",
            // 音频
            "mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "amr", "mid", "mka"
        )
    }
}
