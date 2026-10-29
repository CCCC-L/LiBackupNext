package com.cc.libackupnext.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.cc.libackupnext.MainActivity
import com.cc.libackupnext.service.ProgressService
import com.cc.libackupnext.ui.util.AppIconStore

/**
 * 备份 / 恢复的进度通知。
 *
 * Live Updates（API 36）的流体云是「左边图标 + 右边短文本」两栏：
 * 左边用被备份应用自己的图标（代替系统的下载图标），右边是当前步骤。
 */
object BackupNotifier {

    private const val TAG = "BackupNotifier"
    private const val CHANNEL_ID = "backup_progress"
    const val NOTIFICATION_ID = 0x1001

    private var iconKey: String? = null
    private var icon: Bitmap? = null

    /**
     * 是否还有进行中的备份 / 恢复。
     *
     * 进度回调是 `oneway` 的，最后几个事件可能在操作返回、[finish] 之后才送到，
     * 会把「完成」通知又刷回「正在恢复权限」——结束后直接忽略这些迟到的事件。
     */
    @Volatile
    private var running = false

    /** 只在第一次发现前台服务没了时写日志，避免刷屏。 */
    @Volatile
    private var reportedServiceGone = false


    private fun titleOf(action: String, appName: String, appIndex: Int, appTotal: Int): String =
        if (appTotal > 1) {
            "正在${action} $appName（$appIndex/$appTotal）"
        } else {
            "正在${action} $appName"
        }

    /** 步骤名转成展示文案。 */
    fun stageLabel(stage: String): String = when (stage) {
        "permissions" -> "权限"
        else -> stage
    }

    fun start(
        context: Context,
        action: String,
        appName: String,
        packageName: String,
        userId: Int,
        appIndex: Int,
        appTotal: Int
    ) {
        ensureChannel(context)
        running = true
        reportedServiceGone = false

        // 首帧直接是真实内容（应用名 + 应用图标），避免先闪一下「准备中」
        val initial = build(
            context = context,
            packageName = packageName,
            userId = userId,
            title = titleOf(action, appName, appIndex, appTotal),
            text = "",
            shortText = "",
            ongoing = true
        )

        // 前台服务：不然后台几秒后进程被冻结，通知就不再更新了
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ProgressService::class.java)
                    .putExtra(ProgressService.EXTRA_ACTION, action)
                    .putExtra(ProgressService.EXTRA_NOTIFICATION, initial)
            )
        }.onFailure {
            Log.w(TAG, "start foreground service failed", it)
            ErrorLog.e(TAG, "启动前台服务失败（后台可能被冻结）", it)
        }
    }

    /**
     * 刷新进度。
     *
     * @param stageLabel 当前步骤，例如 apk / obb / user / data / 权限
     * @param stageIndex 当前进行到第几步（从 1 开始）
     * @param stageTotal 这次操作一共几步
     */
    fun update(
        context: Context,
        action: String,
        appName: String,
        packageName: String,
        userId: Int,
        appIndex: Int,
        appTotal: Int,
        stageLabel: String,
        stageIndex: Int,
        stageTotal: Int
    ) {
        // 已经结束了：迟到的进度事件不能把完成态刷回去
        if (!running) return

        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val title = titleOf(action, appName, appIndex, appTotal)
        val text = if (stageLabel.isBlank()) {
            ""
        } else {
            "${action}${stageLabel} · ${stageIndex.coerceAtLeast(1)}/${stageTotal.coerceAtLeast(1)}"
        }

        val notification = build(
            context = context,
            packageName = packageName,
            userId = userId,
            title = title,
            text = text,
            shortText = stageLabel,
            ongoing = true
        )

        // build 期间可能刚好结束，这里再确认一次
        if (!running) return

        // 只走前台服务通道：应用切到后台后，普通 notify 更新这条「前台服务通知」
        // 在部分 ROM（ColorOS / One UI）上会被直接忽略，通知就卡住不动了。
        val service = ProgressService.instance
        if (service != null) {
            service.updateNotification(notification)
        } else {
            // 服务不在（被系统回收 / 启动失败）才退回普通 notify，否则完全没反馈
            if (!reportedServiceGone) {
                reportedServiceGone = true
                ErrorLog.add("通知", "前台服务已不在，退回普通通知（后台可能不刷新）")
            }
            runCatching { manager.notify(NOTIFICATION_ID, notification) }
                .onFailure { Log.w(TAG, "notify update failed", it) }
        }
    }

    /** 结束：显示完成态，几秒后自动收起。 */
    fun finish(context: Context, action: String, failed: Int, appTotal: Int) {
        running = false

        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        // 批量时用「全部…完成」，单个应用还是「…完成」
        val prefix = if (appTotal > 1) "全部${action}" else action
        val title = when {
            failed <= 0 -> "${prefix}完成"
            failed >= appTotal -> "${prefix}失败"
            else -> "${prefix}完成（失败 $failed 个）"
        }

        val notification = build(
            context = context,
            packageName = null,
            userId = 0,
            title = title,
            text = if (appTotal > 1) "共 $appTotal 个应用" else "",
            shortText = "",
            ongoing = false,
            promoted = false
        )

        // 顺序很关键：先退出前台（顺带释放 wake lock），让通知不再是「前台服务通知」，
        // 再把旧的进度通知删掉，最后发一条全新的完成通知。
        //
        // 直接 notify 同一个 id 不够：部分 ROM（ColorOS 的 Live Update / 流体云）在
        // 摘掉前台服务时会用「最后一次 startForeground 的快照」把通知重画一遍，
        // 批量备份耗时长、中间状态多，这一下就把「全部备份完成」盖回「正在备份」了。
        runCatching { ProgressService.instance?.finishAndStop() }
        runCatching { manager.cancel(NOTIFICATION_ID) }
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
            .onFailure { Log.w(TAG, "notify finish failed", it) }

        // 再补一次：cancel + notify 之间如果系统正好在还原前台服务的快照，
        // 这一发会把完成态盖回去。延迟重发一次兜底（setOnlyAlertOnce 不会重复响铃）。
        runCatching {
            Handler(Looper.getMainLooper()).postDelayed({
                if (running) return@postDelayed
                runCatching { manager.notify(NOTIFICATION_ID, notification) }
            }, 600)
        }
    }

    fun cancel(context: Context) {
        running = false

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.cancel(NOTIFICATION_ID) }
        runCatching { ProgressService.instance?.finishAndStop() }
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        // IMPORTANCE_DEFAULT 才允许被提升为 Live Update，靠静音避免打扰
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "备份进度",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "备份 / 恢复时的进度提示"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
        )
    }

    /** 目标应用图标：流体云左边那一格只能是图标，用它代替系统下载图标。 */
    private fun iconFor(context: Context, packageName: String?, userId: Int): Bitmap? {
        if (packageName == null) return null

        val key = "$userId:$packageName"
        if (iconKey == key) return icon

        val bitmap = AppIconStore.cached(packageName, userId)
            ?: runCatching {
                context.packageManager.getApplicationIcon(packageName).toBitmap(96, 96)
            }.getOrNull()

        iconKey = key
        icon = bitmap
        return bitmap
    }

    /** 本应用图标缓存：作为目标图标取不到时的兜底。 */
    private var ownIconCache: Bitmap? = null

    private fun ownIcon(context: Context): Bitmap? {
        ownIconCache?.let { return it }

        val bitmap = runCatching {
            context.packageManager.getApplicationIcon(context.packageName).toBitmap(96, 96)
        }.getOrNull()

        ownIconCache = bitmap
        return bitmap
    }

    /** 前台服务启动时的兜底通知（正常走 start() 里带过去的那个）。 */
    fun buildInitial(context: Context, action: String): Notification =
        build(
            context = context,
            packageName = null,
            userId = 0,
            title = "正在${action}",
            text = "",
            shortText = "",
            ongoing = true
        )

    /** 点通知回到应用主界面。 */
    private fun contentIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun build(
        context: Context,
        packageName: String?,
        userId: Int,
        title: String,
        text: String,
        shortText: String,
        ongoing: Boolean,
        /** 是否请求提升为 Live Update（完成态不要再请求，否则会保留上一次的推广状态）。 */
        promoted: Boolean = ongoing
    ): Notification {
        // 目标应用图标优先（备份谁就显示谁的图标）；
        val appIcon = iconFor(context, packageName, userId) ?: ownIcon(context)

        val smallIcon = if (appIcon != null) {
            Icon.createWithBitmap(appIcon)
        } else {
            Icon.createWithResource(context, android.R.drawable.stat_sys_download)
        }

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(smallIcon)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent(context))
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)

        if (promoted && Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1) {
            builder.setRequestPromotedOngoing(true)

            // 流体云右侧的短文本：当前备份 / 恢复的是哪一部分
            if (shortText.isNotBlank()) {
                builder.setShortCriticalText(shortText)
            }
        }

        return builder.build()
    }
}
