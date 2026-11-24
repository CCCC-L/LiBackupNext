package com.cc.libackupnext.service.root

import com.cc.libackupnext.util.AppLog
import java.io.File

/**
 * 把 `:root` 的 `oom_score_adj` 压到最低，避开 lmkd 与内核 OOM killer。
 *
 * 这是 `:root` 唯一需要的保活手段：libsu 只在最后一个 client 断开时退出，而那时主进程已经没了。
 * 不要改用 libsu 的 daemon 模式 —— 它用 `--nice-name=<包名>:root:daemon` 启动，在按包名扫的 ROM 管家面前更显眼。
 *
 * 往低改需要 `CAP_SYS_RESOURCE`（Magisk su 默认保留），SELinux 也可能拒写，失败只记日志。
 */
internal object RootOomGuard {

    private const val TAG = "RootOomGuard"

    private const val PATH = "/proc/self/oom_score_adj"

    /** -1000 同时作用于 lmkd（按 adj 分档）和内核 `oom_badness`。 */
    private const val LOWEST = "-1000"

    @Volatile
    private var applied = false

    /** 幂等：三个服务各自的 `onCreate` 都会调一次，只有第一次真的写。 */
    fun protect() {
        if (applied) return
        applied = true

        runCatching { File(PATH).writeText(LOWEST) }
            .onSuccess {
                // 读回实际值：SELinux 有时不抛异常但也没写进去
                val actual = runCatching { File(PATH).readText().trim() }.getOrNull()
                AppLog.i(TAG, "oom_score_adj = $actual")
            }
            .onFailure { AppLog.w(TAG, "写 oom_score_adj 失败（不影响功能）", it) }
    }
}
