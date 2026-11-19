package com.cc.libackupnext.service.root

import android.content.Context
import com.cc.libackupnext.data.SnapshotKind
import com.cc.libackupnext.data.json.RestoreTreeItem
import com.cc.libackupnext.data.json.RestoreTreeRequest
import com.cc.libackupnext.service.json.AppJson
import com.cc.libackupnext.service.json.toJsonText
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.nio.ByteBuffer

/**
 * 恢复的底层动作：把快照解到某个路径，再修正归属 / SELinux 上下文。
 *
 * 依赖只有三样：cacheDir（临时解包目录）、native 库错误（提前短路）、
 * nativeRestore（真正解包）。都是构造注入，不读全局状态。
 */
internal class RusticRestoreSupport(
    private val context: Context,
    private val nativeError: String?,
    private val nativeRestore: (ByteBuffer) -> Int,
) {

    /** 从仓库里取回备份文件原文；坏文件抛 IllegalArgumentException 让调用方标成「跳过」。 */
    fun loadJsonFromRepo(id: String, kind: String): String {
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
    fun loadSystemDirFromRepo(id: String, kind: SnapshotKind): File {
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
    fun restoreTree(
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
    fun restoreTo(id: String, dest: String, delete: Boolean): String {
        val payload = RestoreTreeRequest(
            items = listOf(RestoreTreeItem(id = id, dest = dest, delete = delete))
        )
        return runSimple(payload.toJsonText(), nativeRestore)
    }

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
}
