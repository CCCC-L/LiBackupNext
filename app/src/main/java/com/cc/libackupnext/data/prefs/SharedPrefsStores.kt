package com.cc.libackupnext.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.cc.libackupnext.data.RemoteRepo
import com.cc.libackupnext.data.SavedRepo
import com.cc.libackupnext.data.json.RemoteRepoDto
import com.cc.libackupnext.data.json.SavedRepoDto
import com.cc.libackupnext.data.json.toDto
import com.cc.libackupnext.data.json.toModel
import com.cc.libackupnext.data.DefaultBackupDirs
import com.cc.libackupnext.service.json.parseJson
import com.cc.libackupnext.service.json.toJsonText

/**
 * 四个配置接口的 SharedPreferences 实现。
 *
 * 四个类共用一个 [PrefsFile]：同一份 `app_prefs` 文件，只是按职责开了四个窗口。
 * 这样既不会出现「四份文件要同步」的问题，也不会让任何一方看到别人的字段。
 */
private object PrefsFile {

    private const val NAME = "app_prefs"

    @Volatile
    private var cached: SharedPreferences? = null

    fun of(context: Context): SharedPreferences =
        cached ?: context.applicationContext
            .getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .also { cached = it }
}

/** 所有键名集中在这里，避免四个类各写一份字面量。 */
private object Keys {
    const val REPO_PATH = "repo_path"
    const val REPO_PASSWORD = "repo_password"
    const val REPO_OPTIONS = "repo_options"
    const val REMOTE_REPOS = "remote_repos"
    const val SAVED_REPOS = "saved_repos"
    const val SORT_TYPE = "sort_type"
    const val SORT_REVERSE = "sort_reverse"
    const val SHOW_SYSTEM = "show_system_apps"
    const val BACKUP_SELF = "backup_self"
    const val KEEP_SCREEN_ON = "keep_screen_on"
    const val RETENTION_LIMIT = "retention_limit"
    const val FETCH_ICON_FROM_REPO = "fetch_icon_from_repo"
    const val PAUSED_APPS = "paused_apps"
    const val EXTRA_DIRS = "extra_dirs"
    const val CHECK_UPDATE_ON_LAUNCH = "check_update_on_launch"
    const val COLOR_SEED = "color_seed"
    const val COLOR_STYLE = "color_style"
    const val COLOR_SPEC = "color_spec"
    const val VERBOSE_LOG = "verbose_log"
    const val PENDING_BATCH = "pending_batch"

    const val EMPTY_OPTIONS = "{}"
    const val REMOTE_PREFIX = "remote:"
}

/** 默认保留份数：当前这份 + 两次历史。 */
const val DEFAULT_RETENTION_LIMIT = 0

internal class SharedRepoPrefs(context: Context) : RepoPrefs {

    private val prefs = PrefsFile.of(context)

    override val location: String
        get() = prefs.getString(Keys.REPO_PATH, "").orEmpty()

    override val password: String
        get() = prefs.getString(Keys.REPO_PASSWORD, "").orEmpty()

    override val options: String
        get() = prefs.getString(Keys.REPO_OPTIONS, Keys.EMPTY_OPTIONS).orEmpty()

    override val isRemote: Boolean get() = location.startsWith(Keys.REMOTE_PREFIX)

    override val remoteRepos: List<RemoteRepo>
        get() = runCatching {
            prefs.getString(Keys.REMOTE_REPOS, "[]").orEmpty()
                .parseJson<List<RemoteRepoDto>>()
                .map { it.toModel() }
        }.getOrDefault(emptyList())

    override val savedRepos: List<SavedRepo>
        get() = runCatching {
            prefs.getString(Keys.SAVED_REPOS, "[]").orEmpty()
                .parseJson<List<SavedRepoDto>>()
                .map { it.toModel() }
        }.getOrDefault(emptyList())

    override val currentAddress: String
        get() = savedRepos.firstOrNull { it.location == location }?.address ?: location

    override val redactedLocation: String
        get() {
            if (!isRemote) return location
            val id = location.removePrefix(Keys.REMOTE_PREFIX).substringBefore(':')
            return remoteRepos.firstOrNull { it.id == id }?.redacted() ?: "远程仓库"
        }

    override fun remoteConfigFor(location: String): RemoteRepo? {
        if (!location.startsWith(Keys.REMOTE_PREFIX)) return null
        val id = location.removePrefix(Keys.REMOTE_PREFIX).substringBefore(':')
        return remoteRepos.firstOrNull { it.id == id }
    }

    override fun save(location: String, password: String, options: String) {
        prefs.edit {
            putString(Keys.REPO_PATH, location)
                .putString(Keys.REPO_PASSWORD, password)
                .putString(Keys.REPO_OPTIONS, options)
        }
    }

    override fun setRemoteRepos(repos: List<RemoteRepo>) {
        prefs.edit {
            putString(Keys.REMOTE_REPOS, repos.map { it.toDto() }.toJsonText())
        }
    }

    override fun addSavedRepo(record: SavedRepo) {
        writeSavedRepos(savedRepos.filter { it.location != record.location } + record)
    }

    override fun replaceSavedRepo(oldLocation: String, record: SavedRepo) {
        writeSavedRepos(
            savedRepos.filter {
                it.location != oldLocation && it.location != record.location
            } + record
        )
    }

    override fun removeSavedRepo(location: String) {
        writeSavedRepos(savedRepos.filter { it.location != location })
    }

    private fun writeSavedRepos(list: List<SavedRepo>) {
        prefs.edit {
            putString(Keys.SAVED_REPOS, list.map { it.toDto() }.toJsonText())
        }
    }
}

internal class SharedViewPrefs(context: Context) : ViewPrefs {

    private val prefs = PrefsFile.of(context)

    override var sortType: String
        get() = prefs.getString(Keys.SORT_TYPE, "NAME").orEmpty()
        set(value) = prefs.edit { putString(Keys.SORT_TYPE, value) }

    override var sortReverse: Boolean
        get() = prefs.getBoolean(Keys.SORT_REVERSE, false)
        set(value) = prefs.edit { putBoolean(Keys.SORT_REVERSE, value) }

    override var showSystemApps: Boolean
        get() = prefs.getBoolean(Keys.SHOW_SYSTEM, false)
        set(value) = prefs.edit { putBoolean(Keys.SHOW_SYSTEM, value) }

    override var colorSeed: String
        get() = prefs.getString(Keys.COLOR_SEED, "system").orEmpty()
        set(value) = prefs.edit { putString(Keys.COLOR_SEED, value) }

    override var colorStyle: String
        get() = prefs.getString(Keys.COLOR_STYLE, "TonalSpot").orEmpty()
        set(value) = prefs.edit { putString(Keys.COLOR_STYLE, value) }

    override var colorSpec: String
        get() = prefs.getString(Keys.COLOR_SPEC, "SPEC_2025").orEmpty()
        set(value) = prefs.edit { putString(Keys.COLOR_SPEC, value) }

    override var checkUpdateOnLaunch: Boolean
        get() = prefs.getBoolean(Keys.CHECK_UPDATE_ON_LAUNCH, true)
        set(value) = prefs.edit { putBoolean(Keys.CHECK_UPDATE_ON_LAUNCH, value) }

    override var verboseLog: Boolean
        get() = prefs.getBoolean(Keys.VERBOSE_LOG, false)
        set(value) = prefs.edit { putBoolean(Keys.VERBOSE_LOG, value) }
}

internal class SharedBackupPrefs(context: Context) : BackupPrefs {

    private val prefs = PrefsFile.of(context)

    override var backupSelf: Boolean
        get() = prefs.getBoolean(Keys.BACKUP_SELF, true)
        set(value) = prefs.edit { putBoolean(Keys.BACKUP_SELF, value) }

    override var keepScreenOn: Boolean
        get() = prefs.getBoolean(Keys.KEEP_SCREEN_ON, false)
        set(value) = prefs.edit { putBoolean(Keys.KEEP_SCREEN_ON, value) }

    override var retentionLimit: Int
        get() = prefs.getInt(Keys.RETENTION_LIMIT, DEFAULT_RETENTION_LIMIT)
        set(value) = prefs.edit { putInt(Keys.RETENTION_LIMIT, value) }

    override var fetchIconFromRepo: Boolean
        get() = prefs.getBoolean(Keys.FETCH_ICON_FROM_REPO, false)
        set(value) = prefs.edit { putBoolean(Keys.FETCH_ICON_FROM_REPO, value) }

    /**
     * 备份目录（绝对路径），默认的 4 个也在里面。
     *
     * 读出来就按字母序排好 —— 文件页直接拿它渲染，不用再排一次。
     */
    override var extraDirs: Set<String>
        get() = (prefs.getStringSet(Keys.EXTRA_DIRS, null) ?: DefaultBackupDirs.toSet())
            .sorted()
            .toSet()
        set(value) = prefs.edit { putStringSet(Keys.EXTRA_DIRS, value) }

    override fun addPausedApp(key: String) {
        val current = pausedApps()
        if (key in current) return
        prefs.edit { putStringSet(Keys.PAUSED_APPS, current + key) }
    }

    override fun removePausedApp(key: String) {
        val current = pausedApps()
        if (key !in current) return
        prefs.edit { putStringSet(Keys.PAUSED_APPS, current - key) }
    }

    override fun pausedApps(): Set<String> =
        prefs.getStringSet(Keys.PAUSED_APPS, emptySet())?.toSet() ?: emptySet()

    override fun clearPausedApps() {
        prefs.edit { remove(Keys.PAUSED_APPS) }
    }
}

internal class SharedPendingBatchStore(context: Context) : PendingBatchStore {

    private val prefs = PrefsFile.of(context)

    override var json: String
        get() = prefs.getString(Keys.PENDING_BATCH, "").orEmpty()
        set(value) = prefs.edit {
            if (value.isBlank()) {
                remove(Keys.PENDING_BATCH)
            } else {
                putString(Keys.PENDING_BATCH, value)
            }
        }
}
