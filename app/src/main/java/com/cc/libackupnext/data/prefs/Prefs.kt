package com.cc.libackupnext.data.prefs

import com.cc.libackupnext.data.RemoteRepo
import com.cc.libackupnext.data.SavedRepo
import kotlinx.coroutines.flow.StateFlow

/**
 * 本地配置的四个面。
 *
 * 列表页拿 [ViewPrefs]，仓库网关拿 [RepoPrefs]，备份编排拿 [BackupPrefs]。
 *
 * 四个接口都只有 getter / setter 和少量派生方法，没有业务逻辑 ——
 * 「怎么用这些值」在 usecase 里。
 */

/**
 * 仓库信息里**可以给界面看**的部分：位置、地址、清单。
 *
 * 刻意不含密码和 options：UI 需要显示「当前是哪个仓库」「有哪些已保存仓库」，
 * 但没有任何理由能读到密码。单独一个接口，让 UI 层在类型上就拿不到凭据。
 */
interface RepoDisplay {

    /** 已初始化成功的仓库位置：本地文件系统路径，或逻辑标识 `remote:<id>:<path>`。 */
    val location: String

    /** 已添加过的仓库（本地 / 远程），按 location 去重。 */
    val savedRepos: List<SavedRepo>

    /** 页面展示用的当前仓库地址：远程显示真实地址，业务仍用 [location]。 */
    val currentAddress: String

    /**
     * 日志导出用的脱敏地址：远程只留协议和主机，不含凭据。
     *
     * 脱敏规则收在实现里 —— 日志模块只需要一个「能安全写进文件」的字符串，
     * 不该自己判断哪里是密码、哪里是 token。
     */
    val redactedLocation: String
}

/**
 * 仓库位置与凭据。**只有 service 层该拿到它**。
 *
 * 仓库位置和密码由用户决定，产品不代选；这里只负责存取，不做任何默认值推断。
 */
interface RepoPrefs : RepoDisplay {

    val password: String

    /** 透传给 BackendOptions.options 的 JSON；本地仓库是 "{}"。 */
    val options: String

    /** 当前仓库是不是远程的。 */
    val isRemote: Boolean

    /** 已保存的远程服务，按「名称」便捷切换用。 */
    val remoteRepos: List<RemoteRepo>

    fun hasRepository(): Boolean = location.isNotBlank()

    /**
     * 按逻辑标识找远程配置。
     *
     * 有了它，调用方不用自己 `location.startsWith("remote:${repo.id}:")` ——
     * 那个字符串格式是存储细节，不该漏到 service 层。
     */
    fun remoteConfigFor(location: String): RemoteRepo?

    fun save(location: String, password: String, options: String)

    fun setRemoteRepos(repos: List<RemoteRepo>)

    /** 初始化成功：同位置的老记录先删掉，再追加最新的（密码可能改过）。 */
    fun addSavedRepo(record: SavedRepo)

    /** 编辑保存：旧位置和新位置都不能留下重复项。 */
    fun replaceSavedRepo(oldLocation: String, record: SavedRepo)

    /** 只移除应用里的记录（含密码），不动仓库里的备份数据。 */
    fun removeSavedRepo(location: String)
}

/**
 * 界面偏好：排序、筛选、外观、更新检查、日志开关。
 *
 * 这些改了只影响「怎么显示」，不影响仓库里有什么 —— 所以 UI 页面可以放心拿到。
 */
interface ViewPrefs {

    var sortType: String
    var sortReverse: Boolean
    var showSystemApps: Boolean

    /** 种子色："system" 跟随系统壁纸，否则是 6 位 RRGGBB。 */
    var colorSeed: String

    /** PaletteStyle 的名字，例如 TonalSpot / Vibrant。 */
    var colorStyle: String

    /** ColorSpec.SpecVersion 的名字：SPEC_2025 / SPEC_2021。 */
    var colorSpec: String

    /** 每次启动应用时查询最新 Release。 */
    var checkUpdateOnLaunch: Boolean

    /** 打开后 DEBUG 级日志也写进文件。 */
    var verboseLog: Boolean
}

/**
 * 恢复之后的附加动作开关。
 *
 * 单独一个接口而不是塞进 [BackupPrefs]：恢复编排只关心这一件事，
 * 不该为了一个开关去依赖「备份怎么做」的整个面。
 */
interface RestorePrefs {

    /**
     * 恢复完文件夹后是否重建媒体索引。
     *
     * 默认关闭：恢复本身已经完成，这一步是额外的扫描（相册 / 播放器才看得见恢复出来的
     * 文件），要不要付这个代价交给用户决定。
     */
    var rescanMediaAfterRestore: Boolean
}

/**
 * 备份行为与运行期状态：保留数、是否备份自身、常亮、图标来源、暂停列表、目录列表。
 *
 * 这些决定「备份怎么做」，由 usecase 和设置页消费。
 */
interface BackupPrefs : RestorePrefs {

    /** 备份时是否把本程序自己的安装包复制到仓库根目录。 */
    var backupSelf: Boolean

    /** 备份 / 恢复期间是否保持屏幕常亮。 */
    var keepScreenOn: Boolean

    /**
     * 超过几个备份开始清理；`0` 表示不限。
     *
     * 它只是**触发清理的阈值**：真正删哪几行由 `Retention` 按「是否被更新的某一次备份
     * 整行替代」决定，所以实际行数可能比这个值多 —— 孤本和最近一次完整备份都优先于上限。
     */
    var retentionLimit: Int

    /** 是否从仓库里取「没有安装的应用」的图标。 */
    var fetchIconFromRepo: Boolean

    /** 备份目录（绝对路径），默认的几个也在里面；读出来已按字母序排好。 */
    var extraDirs: Set<String>

    /**
     * [extraDirs] 的可观察版本。
     *
     * 界面 collect 它就够了，不要再自己缓存一份 —— 恢复成功后 usecase 会把目录加回来，
     * 缓存的那份不会跟着变。
     */
    val extraDirsFlow: StateFlow<Set<String>>

    /**
     * 记下被 SIGSTOP 暂停的应用（`包名|userId`）。
     *
     * 备份结束会立刻清掉；万一进程被系统杀掉，下次启动据此恢复，
     * 避免应用一直卡在暂停状态（用户看到的就是「应用打不开了」）。
     */
    fun addPausedApp(key: String)
    fun removePausedApp(key: String)
    fun pausedApps(): Set<String>
    fun clearPausedApps()
}

/**
 * 未完成批次（跨应用重启保留）。
 *
 * 单独一个接口而不是塞进 [BackupPrefs]：它是**缓存**不是配置，
 * 语义上是「上次没跑完的活」，将来可能换成数据库或文件，不该和用户设置混在一起。
 */
interface PendingBatchStore {

    /** 未完成批次的 JSON 快照；空串表示没有。 */
    var json: String
}
