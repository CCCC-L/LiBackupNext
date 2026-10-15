package com.cc.libackupnext.data

import android.content.Context
import android.content.SharedPreferences
import com.cc.libackupnext.App
import androidx.core.content.edit

/**
 * 本地配置：已初始化的 rustic 仓库信息 + 列表排序偏好。
 *
 * 只存配置，不存缓存数据。
 */
object AppPrefs {

    private const val NAME = "app_prefs"

    private const val KEY_REPO_PATH = "repo_path"
    private const val KEY_REPO_PASSWORD = "repo_password"
    private const val KEY_SORT_TYPE = "sort_type"
    private const val KEY_SORT_REVERSE = "sort_reverse"
    private const val KEY_SHOW_SYSTEM = "show_system_apps"
    private const val KEY_BACKUP_SELF = "backup_self"
    private const val KEY_FETCH_ICON_FROM_REPO = "fetch_icon_from_repo"
    private const val KEY_PAUSED_APPS = "paused_apps"

    private val prefs: SharedPreferences by lazy {
        App.application.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    }

    // ==================== rustic 仓库 ====================

    /** 已初始化成功的仓库目录（文件系统路径）。 */
    var repoPath: String
        get() = prefs.getString(KEY_REPO_PATH, "").orEmpty()
        private set(value) = prefs.edit { putString(KEY_REPO_PATH, value) }

    var repoPassword: String
        get() = prefs.getString(KEY_REPO_PASSWORD, "").orEmpty()
        private set(value) = prefs.edit { putString(KEY_REPO_PASSWORD, value) }

    /** 初始化 / 校验成功后记录仓库信息。 */
    fun saveRepository(path: String, password: String) {
        prefs.edit {
            putString(KEY_REPO_PATH, path)
                .putString(KEY_REPO_PASSWORD, password)
        }
    }

    /** 备份时是否把 LiBackupNext 自己的安装包复制到仓库根目录。 */
    var backupSelf: Boolean
        get() = prefs.getBoolean(KEY_BACKUP_SELF, true)
        set(value) = prefs.edit { putBoolean(KEY_BACKUP_SELF, value) }

    /** 是否从仓库里取「没有安装的应用」的图标。 */
    var fetchIconFromRepo: Boolean
        get() = prefs.getBoolean(KEY_FETCH_ICON_FROM_REPO, false)
        set(value) = prefs.edit { putBoolean(KEY_FETCH_ICON_FROM_REPO, value) }

    fun clearRepository() {
        prefs.edit {
            remove(KEY_REPO_PATH)
                .remove(KEY_REPO_PASSWORD)
        }
    }

    fun hasRepository(): Boolean = repoPath.isNotBlank()

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
}
