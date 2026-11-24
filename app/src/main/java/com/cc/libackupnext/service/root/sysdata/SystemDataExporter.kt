package com.cc.libackupnext.service.root.sysdata

import android.content.Context
import com.cc.libackupnext.data.SnapshotKind
import com.cc.libackupnext.util.AppLog
import java.io.File

/**
 * 系统数据（Wi-Fi / 联系人 / 通话记录 / 短彩信）导出的统一入口。
 *
 * 只读 ContentProvider 和 WifiManager，**不碰数据库文件** —— 厂商把库挪到别处、
 * 换掉文件名都不会出问题。全程在 root 进程里跑：provider 走 [ProviderAccess] 的外部通道，
 * WifiManager 靠 uid 0 过权限检查，不需要任何运行时权限弹窗。
 *
 * 具体每种数据怎么做由 [SystemDataHandlers] 里的实现决定，这里只负责分派和兜异常。
 * 加一种新类型不需要改这个文件。
 */
internal object SystemDataExporter {

    private const val TAG = "SystemDataExporter"

    /**
     * 系统数据的类型集合：这几个不是文件快照，备份前要由 root 侧导出。
     *
     * 真源在 [SnapshotKind.SYSTEM_KINDS]；这里只是转发，保留旧调用方。
     */
    val SYSTEM_KINDS: List<SnapshotKind> = SnapshotKind.SYSTEM_KINDS

    /**
     * 把内容写进 [dir]（整个目录就是快照内容）；失败返回 false。
     *
     * 带大附件的类型（彩信）走这条：附件是独立文件，不经过内存。
     */
    fun exportTo(context: Context, kind: SnapshotKind, dir: File): Boolean = try {
        SystemDataHandlers[kind]?.exportTo(context, dir) ?: false
    } catch (t: Throwable) {
        // 导出失败会被标成「跳过」，属于典型的功能异常，必须留痕
        AppLog.e(TAG, "导出 ${kind.key} 失败（该项会被跳过）", t)
        false
    }

    /** 列表页副标题用的条数；读不到返回 null。 */
    fun count(context: Context, kind: SnapshotKind): Int? = try {
        SystemDataHandlers[kind]?.count(context)
    } catch (t: Throwable) {
        AppLog.w(TAG, "统计 ${kind.key} 失败", t)
        null
    }
}
