package com.cc.libackupnext.data

import com.cc.libackupnext.entity.DefaultBackupDirs
import android.content.Context
import android.content.SharedPreferences
import com.cc.libackupnext.App
import androidx.core.content.edit
import com.cc.libackupnext.data.json.RemoteRepoDto
import com.cc.libackupnext.data.json.SavedRepoDto
import com.cc.libackupnext.data.json.toDto
import com.cc.libackupnext.data.json.toModel
import com.cc.libackupnext.service.json.parseJson
import com.cc.libackupnext.service.json.toJsonText

/**
 * 本地配置：已初始化的 rustic 仓库信息 + 列表排序偏好。
 *
 * 只存配置，不存缓存数据。
 */
object AppPrefs {

    private const val NAME = "app_prefs"

    private const val KEY_REPO_PATH = "repo_path"
    private const val KEY_REPO_PASSWORD = "repo_password"
    private const val KEY_REPO_OPTIONS = "repo_options"
    private const val KEY_REMOTE_REPOS = "remote_repos"
    private const val KEY_SAVED_REPOS = "saved_repos"
    private const val EMPTY_OPTIONS = "{}"
    private const val KEY_SORT_TYPE = "sort_type"
    private const val KEY_SORT_REVERSE = "sort_reverse"
    private const val KEY_SHOW_SYSTEM = "show_system_apps"
    private const val KEY_BACKUP_SELF = "backup_self"
    private const val KEY_RETENTION_LIMIT = "retention_limit"
    private const val KEY_FETCH_ICON_FROM_REPO = "fetch_icon_from_repo"
    private const val KEY_PAUSED_APPS = "paused_apps"
    private const val KEY_EXTRA_DIRS = "extra_dirs"
    private const val KEY_CHECK_UPDATE_ON_LAUNCH = "check_update_on_launch"
    private const val KEY_COLOR_SEED = "color_seed"
    private const val KEY_COLOR_STYLE = "color_style"
    private const val KEY_COLOR_SPEC = "color_spec"
    private const val KEY_VERBOSE_LOG = "verbose_log"
    private const val KEY_PENDING_BATCH = "pending_batch"

    private val prefs: SharedPreferences by lazy {
        App.application.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    }

    /** 默认保留份数：当前这份 + 两次历史。 */
    const val DEFAULT_RETENTION_LIMIT = 0

    // ==================== rustic 仓库 ====================

    /** 已初始化成功的仓库位置：本地文件系统路径，或逻辑标识 `remote:<id>:<path>`。 */
    var repoPath: String
        get() = prefs.getString(KEY_REPO_PATH, "").orEmpty()
        private set(value) = prefs.edit { putString(KEY_REPO_PATH, value) }

    var repoPassword: String
        get() = prefs.getString(KEY_REPO_PASSWORD, "").orEmpty()
        private set(value) = prefs.edit { putString(KEY_REPO_PASSWORD, value) }

    /** 透传给 BackendOptions.options 的 JSON；本地仓库是 "{}"。 */
    var repoOptions: String
        get() = prefs.getString(KEY_REPO_OPTIONS, EMPTY_OPTIONS).orEmpty()
        private set(value) = prefs.edit { putString(KEY_REPO_OPTIONS, value) }

    /** 当前仓库是不是远程的；启动自检、导出自身 APK 都用它分流。 */
    val isRemote: Boolean get() = repoPath.startsWith("remote:")

    /** 已保存的远程服务，按「名称」便捷切换用。 */
    var remoteRepos: List<RemoteRepo>
        get() = runCatching {
            prefs.getString(KEY_REMOTE_REPOS, "[]").orEmpty()
                .parseJson<List<RemoteRepoDto>>()
                .map { it.toModel() }
        }.getOrDefault(emptyList())
        set(value) = prefs.edit {
            putString(KEY_REMOTE_REPOS, value.map { it.toDto() }.toJsonText())
        }

    /**
     * 已添加过的仓库（本地 / 远程）：初始化成功由业务层写入，切换和编辑也读它。
     * 按 location 去重；当前使用的那套仍以 repoPath / repoPassword / repoOptions 为准。
     */
    var savedRepos: List<SavedRepo>
        get() = runCatching {
            prefs.getString(KEY_SAVED_REPOS, "[]").orEmpty()
                .parseJson<List<SavedRepoDto>>()
                .map { it.toModel() }
        }.getOrDefault(emptyList())
        private set(value) = prefs.edit {
            putString(KEY_SAVED_REPOS, value.map { it.toDto() }.toJsonText())
        }

    /** 初始化成功：同位置的老记录先删掉，再追加最新的（密码可能改过）。 */
    fun addSavedRepo(record: SavedRepo) {
        savedRepos = savedRepos.filter { it.location != record.location } + record
    }

    /** 编辑保存：旧位置和新位置都不能留下重复项。 */
    fun replaceSavedRepo(oldLocation: String, record: SavedRepo) {
        savedRepos = savedRepos.filter {
            it.location != oldLocation && it.location != record.location
        } + record
    }

    /** 只移除应用里的记录（含密码），不动仓库里的备份数据。 */
    fun removeSavedRepo(location: String) {
        savedRepos = savedRepos.filter { it.location != location }
    }

    /** 初始化 / 校验成功后记录仓库信息；远程要连 options 一起存。 */
    fun saveRepository(path: String, password: String, options: String = EMPTY_OPTIONS) {
        prefs.edit {
            putString(KEY_REPO_PATH, path)
                .putString(KEY_REPO_PASSWORD, password)
                .putString(KEY_REPO_OPTIONS, options)
        }
    }

    /** 备份时是否把 LiBackupNext 自己的安装包复制到仓库根目录。 */
    var backupSelf: Boolean
        get() = prefs.getBoolean(KEY_BACKUP_SELF, true)
        set(value) = prefs.edit { putBoolean(KEY_BACKUP_SELF, value) }

    /**
     * 超过几个备份开始清理；`0` 表示不限。
     *
     * 它只是**触发清理的阈值**：真正删哪几行由 [Retention] 按「是否被更新的某一次备份
     * 整行替代」决定，所以实际行数可能比这个值多 —— 孤本和最近一次完整备份都优先于上限。
     */
    var retentionLimit: Int
        get() = prefs.getInt(KEY_RETENTION_LIMIT, DEFAULT_RETENTION_LIMIT)
        set(value) = prefs.edit { putInt(KEY_RETENTION_LIMIT, value) }

    /** 是否从仓库里取「没有安装的应用」的图标。 */
    var fetchIconFromRepo: Boolean
        get() = prefs.getBoolean(KEY_FETCH_ICON_FROM_REPO, false)
        set(value) = prefs.edit { putBoolean(KEY_FETCH_ICON_FROM_REPO, value) }

    fun hasRepository(): Boolean = repoPath.isNotBlank()

    /** 页面展示的当前仓库地址：远程显示真实地址，业务仍用 [repoPath] 这个逻辑标识。 */
    val currentRepoAddress: String
        get() = savedRepos.firstOrNull { it.location == repoPath }?.address ?: repoPath

    // ==================== 更新检查 ====================

    /** 每次启动应用时查询 GitHub 最新 Release；默认开启。 */
    var checkUpdateOnLaunch: Boolean
        get() = prefs.getBoolean(KEY_CHECK_UPDATE_ON_LAUNCH, true)
        set(value) = prefs.edit { putBoolean(KEY_CHECK_UPDATE_ON_LAUNCH, value) }

    // ==================== 外观（动态配色） ====================

    /** 种子色："system" 跟随系统壁纸，否则是 6 位 RRGGBB。 */
    var colorSeed: String
        get() = prefs.getString(KEY_COLOR_SEED, "system").orEmpty()
        set(value) = prefs.edit { putString(KEY_COLOR_SEED, value) }

    /** PaletteStyle 的名字，例如 TonalSpot / Vibrant。 */
    var colorStyle: String
        get() = prefs.getString(KEY_COLOR_STYLE, "TonalSpot").orEmpty()
        set(value) = prefs.edit { putString(KEY_COLOR_STYLE, value) }

    /** ColorSpec.SpecVersion 的名字：SPEC_2025 / SPEC_2021。 */
    var colorSpec: String
        get() = prefs.getString(KEY_COLOR_SPEC, "SPEC_2025").orEmpty()
        set(value) = prefs.edit { putString(KEY_COLOR_SPEC, value) }

    // ==================== 日志 ====================

    /** 打开后 DEBUG 级日志也写进文件，复现疑难问题时再开，避免平时刷屏。 */
    var verboseLog: Boolean
        get() = prefs.getBoolean(KEY_VERBOSE_LOG, false)
        set(value) = prefs.edit { putBoolean(KEY_VERBOSE_LOG, value) }

    // ==================== 列表排序 ====================

    var sortType: String
        get() = prefs.getString(KEY_SORT_TYPE, "NAME").orEmpty()
        set(value) = prefs.edit { putString(KEY_SORT_TYPE, value) }

    var sortReverse: Boolean
        get() = prefs.getBoolean(KEY_SORT_REVERSE, false)
        set(value) = prefs.edit { putBoolean(KEY_SORT_REVERSE, value) }

    var showSystemApps: Boolean
        get() = prefs.getBoolean(KEY_SHOW_SYSTEM, false)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_SYSTEM, value) }

    // ==================== 未完成批次（跨应用重启保留） ====================

    /**
     * 未完成批次的 JSON 快照。
     */
    var pendingBatchJson: String
        get() = prefs.getString(KEY_PENDING_BATCH, "").orEmpty()
        set(value) = prefs.edit {
            if (value.isBlank()) {
                remove(KEY_PENDING_BATCH)
            } else {
                putString(KEY_PENDING_BATCH, value)
            }
        }

    // ==================== 备份期间被暂停的应用 ====================

    /**
     * 记下被 SIGSTOP 暂停的应用（`包名|userId`）。
     *
     * 备份结束会立刻清掉；万一进程被系统杀掉，下次启动据此恢复，
     * 避免应用一直卡在暂停状态（用户看到的就是「应用打不开了」）。
     */
    fun addPausedApp(key: String) {
        val current = pausedApps()
        if (key in current) return
        prefs.edit { putStringSet(KEY_PAUSED_APPS, current + key) }
    }

    fun removePausedApp(key: String) {
        val current = pausedApps()
        if (key !in current) return
        prefs.edit { putStringSet(KEY_PAUSED_APPS, current - key) }
    }

    fun pausedApps(): Set<String> =
        prefs.getStringSet(KEY_PAUSED_APPS, emptySet())?.toSet() ?: emptySet()

    fun clearPausedApps() {
        prefs.edit { remove(KEY_PAUSED_APPS) }
    }

    // ==================== 文件备份 ====================

    /**
     * 备份目录（绝对路径），默认的 4 个也在里面。
     *
     * 读出来就按字母序排好 —— 文件页直接拿它渲染，不用再排一次。
     */
    var extraDirs: Set<String>
        get() = (prefs.getStringSet(KEY_EXTRA_DIRS, null) ?: DefaultBackupDirs.toSet())
            .sorted()
            .toSet()

        set(value) = prefs.edit { putStringSet(KEY_EXTRA_DIRS, value) }
}
