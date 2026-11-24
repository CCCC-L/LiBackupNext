package com.cc.libackupnext.util

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.system.Os
import android.util.Log
import android.widget.Toast
import com.cc.libackupnext.BuildConfig
import com.cc.libackupnext.data.prefs.RepoDisplay
import com.cc.libackupnext.util.AppLog.format
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 全进程日志：每条实时落盘，用来复盘崩溃**和**功能异常。
 *
 * 写路径只有一步：往长连接的 FileOutputStream 直接 write（内核页缓存），
 * 不套缓冲、不起后台线程、不逐条 fsync。App 进程被杀不会丢页缓存数据，
 * 只有 FATAL 的崩溃报告写完才 sync。这样非崩溃场景随时导出都是完整的。
 *
 * 主进程走 App.onCreate，:root 进程走各自 RootService.onCreate —— libsu 的
 * RootServerMain 只创建 package Context 并直接 new Service，**不会实例化 Application**，
 * 所以 :root 侧不能依赖 App.onCreate。两边各写各的 main.log / root.log，
 * 共享同一个 filesDir —— 两进程都是同一 UID，但 root 进程以 uid 0 建文件，
 * 所以目录和文件都显式 chmod，保证主进程也能写、也能清理。
 */
object AppLog {

    enum class Level { DEBUG, INFO, WARN, ERROR, FATAL }

    /** 崩溃续命后重建 Activity 用的标记：跳过引导页、直落设置 tab。 */
    const val EXTRA_CRASH_RECOVERY = "libackup.crash_recovery"

    private const val LOG_DIR = "logs"
    private const val MAX_MESSAGE = 2000
    private const val MAX_STACK = 16000
    private const val MAX_CRUMBS = 120
    private const val RETAIN_MILLIS = 7L * 24 * 60 * 60 * 1000

    /** 每份 256KB、留 2 份备份。不用全局按大小删：那会删掉另一个进程正在写的文件。 */
    private const val MAX_FILE_BYTES = 256L * 1024
    private const val MAX_BACKUPS = 2
    private const val MAX_CRASH_FILES = 10

    /** 操作索引一样不能无限长：超过就整体挪成 .1，只留一份历史。 */
    private const val MAX_OPERATIONS_BYTES = 256L * 1024

    /** 每写这么多条检查一次轮转。比每条 stat 便宜，128 条最多几十 KB，不会撑爆上限。 */
    private const val ROTATE_CHECK_INTERVAL = 128
    private const val CRASH_PREFIX = "crash-"

    private const val CRASH_WINDOW_MS = 10_000L
    private const val CRASH_LIMIT = 5

    /** 0777 / 0666：Kotlin 没有八进制字面量。 */
    private const val MODE_DIR = 0x1FF
    private const val MODE_FILE = 0x1B6

    private val writeLock = Any()
    private val crumbLock = Any()
    private val crumbs = ArrayDeque<String>()

    private val crashCount = AtomicInteger(0)
    private val crashWindow = AtomicLong(0L)

    private lateinit var appContext: Context
    private lateinit var logDir: File
    private lateinit var logFile: File
    private lateinit var operationsFile: File
    private lateinit var pendingFile: File
    private lateinit var sessionId: String

    private var process: String = "main"
    private var stream: FileOutputStream? = null
    private var sinceRotateCheck = 0
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @Volatile
    private var installed = false

    @Volatile
    private var verbose = false

    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val fullFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US)

    /** 崩溃收尾（恢复 SIGSTOP 应用等重活）由 UI 层注册，AppLog 不反向依赖业务层。 */
    @Volatile
    var recoveryHook: (() -> Unit)? = null

    /** 栈顶 Activity，崩溃后用来同进程重建。 */
    @Volatile
    private var topActivity: Activity? = null

    private val secretPattern = Regex(
        "(?i)(password|passwd|pwd|secret|token|preSharedKey|psk|apiKey)" +
                "(\"?\\s*[:=]\\s*)(\"[^\"]*\"|[^\\s,}&]+)"
    )

    /** 导出时统一脱敏；写入路径不跑正则，避免成为高频热点。 */
    fun sanitize(raw: String): String = secretPattern.replace(raw) {
        it.groupValues[1] + it.groupValues[2] + "***"
    }

    private fun nowText(): String = synchronized(dateFormat) { dateFormat.format(Date()) }
    private fun fullTimeText(): String = synchronized(fullFormat) { fullFormat.format(Date()) }

    // ==================== 安装 ====================

    fun install(app: Application) = install(app.applicationContext, app)

    /**
     * 配置读取口：主进程在 [com.cc.libackupnext.App.onCreate] 里注入，
     * `:root` 进程不注入（那边没有 Application，也不该读用户配置）。
     *
     * 用函数而不是直接持有对象：注入时机在 install 里，之后配置随时可变，
     * 每次读都取最新值。
     */
    private var verboseProvider: () -> Boolean = { false }
    private var repoProvider: () -> RepoDisplay? = { null }

    fun install(
        context: Context,
        app: Application?,
        verbose: () -> Boolean,
        repo: () -> RepoDisplay?,
    ) {
        verboseProvider = verbose
        repoProvider = repo
        install(context, app)
    }

    /**
     * 主进程传 Application（要注册 Activity 生命周期回调）；
     * :root 进程只有 package Context，传 [context] 即可，活动栈回调用不上。
     */
    fun install(context: Context, app: Application? = null) {
        if (installed) return
        appContext = context

        // libsu 的 root 进程不设进程名，只能用 uid 判：uid 0 即 :root
        process = if (Process.myUid() == 0) "root" else "main"
        sessionId = "${System.currentTimeMillis()}-${Process.myPid()}"

        logDir = File(appContext.filesDir, LOG_DIR)
        logFile = File(logDir, "$process.log")
        operationsFile = File(logDir, "operations.log")
        pendingFile = File(logDir, "pending_crash")

        runCatching { logDir.mkdirs() }
        makeSharedWritable(logDir, MODE_DIR)
        runCatching { ensureStream() }
        makeSharedWritable(logFile, MODE_FILE)
        verbose = runCatching { verboseProvider() }.getOrDefault(false)

        installed = true
        app?.registerActivityLifecycleCallbacks(activityCallbacks)
        installCrashHandler()

        writeLine(
            format(Level.INFO, "Session", "=== 会话开始 === $sessionId", null),
            toFile = true,
            sync = false
        )

        trim()
    }

    private val activityCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            topActivity = activity
        }

        override fun onActivityStarted(activity: Activity) {
            topActivity = activity
        }

        override fun onActivityResumed(activity: Activity) {
            topActivity = activity
        }

        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

        override fun onActivityDestroyed(activity: Activity) {
            if (topActivity === activity) topActivity = null
        }
    }

    private fun ensureStream(): FileOutputStream {
        stream?.let { return it }
        val opened = FileOutputStream(logFile, true)
        stream = opened
        return opened
    }

    /**
     * root 进程以 uid 0 建的目录/文件，主进程（普通 uid）也要能写、能删，统一放宽权限位。
     */
    private fun makeSharedWritable(file: File, mode: Int) {
        runCatching { Os.chmod(file.absolutePath, mode) }
    }

    // ==================== 写入 ====================

    fun d(tag: String, message: String, throwable: Throwable? = null, op: String? = null) =
        log(Level.DEBUG, tag, message, throwable, op)

    fun i(tag: String, message: String, throwable: Throwable? = null, op: String? = null) =
        log(Level.INFO, tag, message, throwable, op)

    fun w(tag: String, message: String, throwable: Throwable? = null, op: String? = null) =
        log(Level.WARN, tag, message, throwable, op)

    fun e(tag: String, message: String, throwable: Throwable? = null, op: String? = null) =
        log(Level.ERROR, tag, message, throwable, op)

    /**
     * 长文本完整落盘：按分片编号写入，绕过 [format] 的单条 MAX_MESSAGE 截断。
     */
    fun logLong(level: Level, tag: String, message: String, op: String? = null) {
        val clean = message.replace('\n', ' ').replace('\r', ' ')
        val chunkSize = MAX_MESSAGE - 24
        val chunks = clean.chunked(chunkSize).ifEmpty { listOf("") }
        chunks.forEachIndexed { index, chunk ->
            log(level, tag, "[${index + 1}/${chunks.size}] $chunk", null, op)
        }
    }

    private fun log(
        level: Level,
        tag: String,
        message: String,
        throwable: Throwable?,
        op: String?
    ) {
        // 协程取消是正常流程，不是错误
        if (throwable is CancellationException) return

        val text = format(level, tag, message, op)
        val toFile = level != Level.DEBUG || verbose
        writeLine(text, toFile = toFile, sync = false)

        val stack = throwable?.stackTraceToString()?.take(MAX_STACK)
        if (!stack.isNullOrBlank()) {
            writeLine(stack, toFile = toFile, sync = false)
        }

        when (level) {
            Level.DEBUG -> Log.d(tag, message, throwable)
            Level.INFO -> Log.i(tag, message, throwable)
            Level.WARN -> Log.w(tag, message, throwable)
            Level.ERROR, Level.FATAL -> Log.e(tag, message, throwable)
        }
    }

    private fun format(level: Level, tag: String, message: String, op: String?): String {
        val clean = message
            .replace('\n', ' ')
            .replace('\r', ' ')
            .take(MAX_MESSAGE)
        val opPart = op?.let { " op=$it" }.orEmpty()
        return "${nowText()} ${level.name.first()} [$process]$opPart $tag | " +
                clean.ifBlank { "（空消息）" }
    }

    /** 只做一次 write，临界区极短；不 fsync。 */
    private fun writeLine(text: String, toFile: Boolean, sync: Boolean) {
        synchronized(crumbLock) {
            crumbs.addLast(text)
            if (crumbs.size > MAX_CRUMBS) crumbs.removeFirst()
        }
        if (!toFile) return

        synchronized(writeLock) {
            runCatching {
                maybeRotate()
                val out = ensureStream()
                out.write((text + "\n").toByteArray(Charsets.UTF_8))
                if (sync) out.fd.sync()
            }
        }
    }

    /** 每 [ROTATE_CHECK_INTERVAL] 条检查一次：文件被删则重开流，超限则轮转。 */
    private fun maybeRotate() {
        if (++sinceRotateCheck < ROTATE_CHECK_INTERVAL) return
        sinceRotateCheck = 0

        if (!logFile.exists()) {
            runCatching { stream?.close() }
            stream = null
            return
        }
        if (logFile.length() < MAX_FILE_BYTES) return
        rotate()
    }

    /** main.log → main.log.1 → … → main.log.N，最老的丢掉。顺序由 [LogRetention.rotationPlan] 决定。 */
    private fun rotate() {
        runCatching { stream?.close() }
        stream = null

        LogRetention.rotationPlan(logFile.name, MAX_BACKUPS).forEach { (from, to) ->
            val source = File(logDir, from)
            if (!source.exists()) return@forEach
            val target = File(logDir, to)
            runCatching { target.delete() }
            runCatching { source.renameTo(target) }
        }

        makeSharedWritable(File(logDir, "${logFile.name}.1"), MODE_FILE)
    }

    // ==================== 操作轨迹 ====================

    /** 一次业务操作一个 id，随请求 JSON 透传到 :root，日志按它聚合。 */
    fun startOperation(type: String, target: String): String {
        val op = "${System.currentTimeMillis().toString(36)}-" +
                (Math.random() * 0xFFFF).toInt().toString(16)
        i("Operation", "开始 type=$type target=$target", op = op)
        return op
    }

    fun finishOperation(
        op: String,
        type: String,
        target: String,
        startedAt: Long,
        outcome: String
    ) {
        val duration = System.currentTimeMillis() - startedAt
        i("Operation", "结束 type=$type 结果=$outcome 耗时=${duration}ms", op = op)
        val row = "${fullTimeText()} | $op | $type | $target | ${duration}ms | $outcome\n"
        synchronized(writeLock) {
            runCatching {
                FileOutputStream(
                    operationsFile,
                    true
                ).use { it.write(row.toByteArray()) }
            }
        }
        makeSharedWritable(operationsFile, MODE_FILE)
        rotateOperations()
    }

    /** 操作索引超过上限就整体挪成 .1，只留一份历史。 */
    private fun rotateOperations() {
        if (operationsFile.length() < MAX_OPERATIONS_BYTES) return
        val old = File(logDir, "${operationsFile.name}.1")
        runCatching { old.delete() }
        runCatching { operationsFile.renameTo(old) }
        makeSharedWritable(old, MODE_FILE)
    }

    /** 用户点击 / 页面切换这类 breadcrumb，功能异常时靠它还原操作路径。 */
    fun breadcrumb(tag: String, message: String) = i("Breadcrumb", "$tag: $message")

    fun operationsText(): String = buildString {
        append("操作索引（一次业务操作一行，用 op 值去日志里搜同一次操作的完整链路）\n")
        append("时间 | op | 类型 | 目标 | 耗时 | 结果\n")
        append("-".repeat(80)).append('\n')
        append(runCatching { operationsFile.readText() }.getOrDefault("（暂无操作记录）\n"))
    }

    // ==================== 崩溃：原地续命 ====================

    private fun installCrashHandler() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()

        if (process == "root") {
            // :root 没有 Activity，续命、拉起界面、标记待恢复全都无意义，
            // 而且 markPendingCrash 会让主进程下次启动误以为主进程崩过。
            // 这里只留证据，然后让进程照常死掉——客户端会自动重绑。
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                writeCrashReport(thread, error)
                previousHandler?.uncaughtException(thread, error)
            }
            return
        }

        Thread.setDefaultUncaughtExceptionHandler { thread, error -> handleCrash(thread, error) }
    }

    private fun handleCrash(thread: Thread, error: Throwable) {
        val isMain = thread.name == "main"
        writeLine(
            format(
                Level.FATAL,
                "Uncaught",
                "${thread.name}: ${error.message ?: error.javaClass.name}",
                null
            ),
            toFile = true,
            sync = false
        )
        writeLine(error.stackTraceToString().take(MAX_STACK), toFile = true, sync = false)
        writeCrashReport(thread, error)
        markPendingCrash()
        val allow = countAndAllow()

        if (isMain && allow) {
            toastOnMain(
                "应用遇到问题，日志已保存，可在 设置 → 导出日志 查看",
                longToast = true
            )

            // 重活放到恢复后的消息队列里做：handler 里做跨进程 AIDL 会卡住续命
            Handler(Looper.getMainLooper()).post {
                runCatching { recoveryHook?.invoke() }
                runCatching { relaunchTopActivity() }
            }
            reviveMainLoop()
        } else if (isMain) {
            // 熔断：交回系统，避免陷入「反复抛异常 + 卡死不退出」
            previousHandler?.uncaughtException(thread, error)
        }
    }

    /** 重新进入消息循环：不交系统 handler，进程不死，界面继续可用。 */
    private fun reviveMainLoop() {
        while (true) {
            try {
                Looper.loop()
                return  // Looper 正常退出，不再续命
            } catch (t: Throwable) {
                writeLine(
                    format(Level.ERROR, "Revive", "续命后再次异常：${t.message}", null),
                    toFile = true,
                    sync = false
                )
                if (!countAndAllow()) {
                    previousHandler?.uncaughtException(Thread.currentThread(), t)
                    return
                }
            }
        }
    }

    private fun countAndAllow(): Boolean {
        val now = System.currentTimeMillis()
        if (now - crashWindow.get() > CRASH_WINDOW_MS) {
            crashWindow.set(now)
            crashCount.set(0)
        }
        return crashCount.incrementAndGet() <= CRASH_LIMIT
    }

    /** 同进程重建：不回桌面、不重启进程，但跳过引导页直落设置 tab。 */
    private fun relaunchTopActivity() {
        val intent = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
            ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        intent.putExtra(EXTRA_CRASH_RECOVERY, true)
        runCatching { appContext.startActivity(intent) }
    }

    // ==================== 全应用 Toast ====================

    // lazy 而不是直接初始化：JVM 单测里没有 Looper，类加载时就建 Handler 会让
    // 整个 AppLog 初始化失败（连带任何调用 AppLog 的纯逻辑都没法测）
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var toast: Toast? = null   // 只在主线程读写

    /** 全应用共用的吐司提示：先关掉上一条，再显示新的一条。可从任意线程调用。 */
    fun toastOnMain(text: String, longToast: Boolean = false) {
        val duration = if (longToast) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        val block = {
            try {
                toast?.cancel()
                toast = Toast.makeText(appContext, text, duration).also { it.show() }
            } catch (e: Exception) {
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun writeCrashReport(thread: Thread, error: Throwable) {
        val file =
            File(logDir, "$CRASH_PREFIX${System.currentTimeMillis()}-${Process.myPid()}-$process.txt")
        val text = buildString {
            append(environmentText())
            append("\n===== 未捕获异常 =====\n")
            append(fullTimeText()).append("  thread=").append(thread.name)
                .append("  process=").append(process).append('\n')
            append(error.javaClass.name).append(": ").append(error.message ?: "").append('\n')
            append(error.stackTraceToString().take(MAX_STACK)).append('\n')
            append("\n===== 崩溃前轨迹（最近 $MAX_CRUMBS 条）=====\n")
            synchronized(crumbLock) { crumbs.forEach { append(it).append('\n') } }
        }
        runCatching {
            FileOutputStream(file, false).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
        }
        makeSharedWritable(file, MODE_FILE)
    }

    private fun markPendingCrash() {
        runCatching { pendingFile.writeText(sessionId) }
    }

    fun hasPendingCrash(): Boolean = runCatching { pendingFile.exists() }.getOrDefault(false)

    fun consumePendingCrash() {
        runCatching { pendingFile.delete() }
    }

    // ==================== 文件与导出辅助 ====================

    fun logFiles(): List<File> = runCatching {
        logDir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()
    }.getOrDefault(emptyList())

    fun setVerbose(enabled: Boolean) {
        verbose = enabled
    }

    /** 只清崩溃报告；主日志各进程自己轮转，删对方的文件会让它继续写已删除的 inode。 */
    private fun trim() {
        runCatching {
            val crashes = logFiles().filter { it.name.startsWith(CRASH_PREFIX) }
            if (crashes.isEmpty()) return@runCatching

            val victims = LogRetention.crashesToDelete(
                crashes = crashes.map { LogRetention.CrashFile(it.name, it.lastModified()) },
                maxFiles = MAX_CRASH_FILES,
                now = System.currentTimeMillis(),
                retainMillis = RETAIN_MILLIS
            ).toSet()

            crashes.filter { it.name in victims }.forEach { it.delete() }
        }
    }

    /** 导出包里的环境信息：软件版本 + 设备 / 系统核心字段。 */
    fun environmentText(): String = buildString {
        append("LiBackupNext 环境信息\n")
        append("=".repeat(48)).append('\n')
        append("生成时间: ").append(fullTimeText()).append('\n')
        append("会话: ").append(if (::sessionId.isInitialized) sessionId else "未初始化")
            .append('\n')
        append("进程: ").append(process).append("  pid=").append(Process.myPid()).append('\n')

        append("\n--- 软件版本 ---\n")
        append("版本名: ").append(BuildConfig.APP_VERSION).append('\n')
        append("版本号: ").append(runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).longVersionCode
        }.getOrDefault(-1L)).append('\n')
        append("构建类型: ").append(if (BuildConfig.DEBUG) "debug" else "release").append('\n')
        append("包名: ").append(appContext.packageName).append('\n')
        append("rustic_core: ").append(BuildConfig.RUSTIC_CORE_VERSION).append('\n')
        append("rustic_backend: ").append(BuildConfig.RUSTIC_BACKEND_VERSION).append('\n')
        // 远程 location 本身不含凭据，但也别把服务地址整条写进导出包
        append("仓库路径: ").append(
            runCatching { repoProvider()?.redactedLocation.orEmpty() }.getOrDefault("")
        ).append('\n')
        append("详细日志: ").append(if (verbose) "开" else "关").append('\n')

        append("\n--- 设备 ---\n")
        append("品牌: ").append(Build.BRAND).append('\n')
        append("型号: ").append(Build.MODEL).append('\n')

        append("\n--- 系统 ---\n")
        append("Android 版本: ").append(Build.VERSION.RELEASE).append('\n')
        append("SDK: ").append(Build.VERSION.SDK_INT).append('\n')
    }
}
