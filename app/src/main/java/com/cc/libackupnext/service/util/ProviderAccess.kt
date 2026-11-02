package com.cc.libackupnext.service.util

import android.app.IActivityManager
import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.IContentProvider
import android.database.Cursor
import android.database.CursorWrapper
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.os.ServiceManager
import android.util.Log

/**
 * 系统数据（Wi-Fi / 联系人 / 通话记录）访问 provider 的入口。
 *
 * 两个进程里跑的东西不一样：
 * - **主进程**：直接用 ContentResolver，权限由 root 侧静默授予；
 * - **root 服务进程**：不在 AMS 的进程表里，`ContentResolver.query` 会被 AMS 拒
 *   （`Unable to find app for caller ... when getting content provider ...`），
 *   所以走 `getContentProviderExternal` 这条外部通道 —— 它不查进程记录，uid 0 直接放行，
 *   `adb shell content query` 走的就是它。
 *
 * 调用方不用关心在哪一边，[query] / [insert] / [applyBatch] 自己分派。
 */
internal object ProviderAccess {

    private const val TAG = "ProviderAccess"

    /** 系统数据只备份主用户（和文件夹备份一致），所以这里固定 0。 */
    private const val USER_ID = 0

    private val activityManager: IActivityManager? by lazy {
        runCatching { IActivityManager.Stub.asInterface(ServiceManager.getService("activity")) }
            .onFailure { Log.e(TAG, "拿不到 ActivityManager", it) }
            .getOrNull()
    }

    /**
     * 当前进程要不要走外部通道。
     *
     * 只有 root 进程需要（也才有 `ACCESS_CONTENT_PROVIDERS_EXTERNALLY`）；minSdk 已经是 31，
     * 所以 `IContentProvider.query` 的 `AttributionSource` 参数在所有支持的版本上都成立。
     */
    private val useExternalChannel: Boolean
        get() = Process.myUid() == 0

    fun query(
        context: Context,
        uri: Uri,
        projection: Array<String>? = null,
        selection: String? = null,
        selectionArgs: Array<String>? = null,
        sortOrder: String? = null
    ): Cursor? {
        if (!useExternalChannel) {
            return context.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
        }

        val connection = connect(context, uri.authority) ?: return null
        val args = Bundle().apply {
            selection?.let { putString(ContentResolver.QUERY_ARG_SQL_SELECTION, it) }
            selectionArgs?.let { putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, it) }
            sortOrder?.let { putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, it) }
        }

        val cursor = try {
            connection.provider.query(context.attributionSource, uri, projection, args, null)
        } catch (t: Throwable) {
            connection.release()
            throw t
        }

        if (cursor == null) {
            connection.release()
            return null
        }

        // 引用要留到 cursor 关闭：提前还掉的话，大结果集读到一半 provider 可能被回收
        return ReleasingCursor(cursor) { connection.release() }
    }

    /** 一次写一批，返回实际写入的行数。 */
    fun bulkInsert(context: Context, uri: Uri, values: Array<ContentValues>): Int {
        if (!useExternalChannel) return context.contentResolver.bulkInsert(uri, values)

        val connection = connect(context, uri.authority) ?: return 0
        return try {
            connection.provider.bulkInsert(context.attributionSource, uri, values)
        } finally {
            connection.release()
        }
    }

    fun applyBatch(
        context: Context,
        authority: String,
        operations: List<ContentProviderOperation>
    ): Array<ContentProviderResult>? {
        // 两边（ContentResolver / IContentProvider）都要 ArrayList
        val batch = ArrayList(operations)
        if (!useExternalChannel) return context.contentResolver.applyBatch(authority, batch)

        val connection = connect(context, authority) ?: return null
        return try {
            connection.provider.applyBatch(context.attributionSource, authority, batch)
        } finally {
            connection.release()
        }
    }

    /** 一次外部通道连接：provider binder + 用来还引用的 token。 */
    private class Connection(
        private val manager: IActivityManager,
        private val authority: String,
        private val token: IBinder,
        val provider: IContentProvider
    ) {
        private var released = false

        fun release() {
            if (released) return
            released = true
            runCatching { manager.removeContentProviderExternalAsUser(authority, token, USER_ID) }
        }
    }

    private fun connect(context: Context, authority: String?): Connection? {
        val manager = activityManager ?: return null
        val name = authority ?: return null
        val token = Binder()

        val provider = runCatching {
            manager.getContentProviderExternal(name, USER_ID, token, context.packageName)?.provider
        }.onFailure { Log.e(TAG, "取不到 provider：$name", it) }
            .getOrNull() ?: return null

        return Connection(manager, name, token, provider)
    }

    /** 关掉的时候把 provider 引用还回去。 */
    private class ReleasingCursor(
        cursor: Cursor,
        private val release: () -> Unit
    ) : CursorWrapper(cursor) {

        private var released = false

        override fun close() {
            super.close()
            if (released) return
            released = true
            release()
        }
    }
}
