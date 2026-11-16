package com.cc.libackupnext.data

import com.cc.libackupnext.data.protocol.LocalProtocol
import com.cc.libackupnext.data.protocol.RepoProtocol
import com.cc.libackupnext.data.protocol.RepoProtocols

/**
 * 一份仓库配置（本地 / 远程共用）。用户只填协议 / 名称 / 地址 / 认证方式 / 凭据 / 路径，
 * 仓库位置（location）和后端选项由这里与 service 层派生，界面上不出现。
 *
 * 协议相关的字段全部装在 [values] 里，键由 data/protocol 下的协议文件自己声明，
 * 所以新增协议不用改这个类型。
 * 本地协议的 [path] 就是文件系统目录；远程协议的 [path] 是服务里的相对路径。
 */
data class RemoteRepo(
    val id: String = System.currentTimeMillis().toString(),
    val name: String = LocalProtocol.defaultName,
    val protocolKey: String = LocalProtocol.key,
    val path: String = "",
    val values: Map<String, String> = emptyMap()
) {
    val protocol: RepoProtocol get() = RepoProtocols.of(protocolKey)

    /** 读协议字段，未填时是空串。 */
    operator fun get(field: String): String = values[field].orEmpty()

    /** 写协议字段；不要手拼 copy(values = …)，免得把其它字段丢了。 */
    fun with(field: String, value: String): RemoteRepo = copy(values = values + (field to value))

    /** 开关字段统一存 "true" / 缺失，关掉时把键删掉，别留空串。 */
    fun setFlag(field: String, value: Boolean): RemoteRepo =
        copy(values = if (value) values + (field to "true") else values - field)

    /** 开关字段统一存 "true" / 缺失。 */
    fun flag(field: String): Boolean = values[field] == "true"

    /** 本地直接是目录路径；远程用逻辑标识，Rust 侧据此起 restic 服务。 */
    val location: String
        get() = if (protocol.isLocal) path else "remote:$id:${path.trimStart('/')}"

    /** 展示 / 日志用，绝不带密码或私钥。 */
    fun redacted(): String = protocol.describe(this)

    /** 按协议判断必填项；本地只要目录，远程还要地址与对应凭据。 */
    fun isComplete(): Boolean =
        if (protocol.isLocal) path.isNotBlank()
        else name.isNotBlank() && protocol.isComplete(this)

    /**
     * 初始化 / 保存仓库的前置条件：远程必须先选过仓库路径，
     * 否则空路径会把整个服务根当成仓库。测连接和路径浏览仍只看 [isComplete]。
     */
    fun canInit(): Boolean = isComplete() && (protocol.isLocal || path.isNotBlank())

    /** 切换协议：清空输入框，名称没被用户改过时跟着协议名走。 */
    fun switchedTo(next: RepoProtocol): RemoteRepo {
        if (next.key == protocolKey) return this
        val keepName = name.isNotBlank() && name != protocol.defaultName
        return RemoteRepo(
            id = id,
            name = if (keepName) name else next.defaultName,
            protocolKey = next.key
        )
    }

    /** 清单 JSON 的编解码见 data/json/RepoPrefsDto.kt，业务类型本身不碰 JSON。 */
}

/** 文件夹选择弹窗的数据源：本地文件系统，或远程服务。 */
enum class FolderPickerMode { LOCAL, REMOTE }

/**
 * 打开文件夹选择弹窗的参数（窗口由 Activity 顶层持有）。
 *
 * [initialPath] 为空时由弹窗向业务层取默认起点：本地是内部存储根目录，远程是服务根。
 * [allowRoot] 只影响本地模式：true 能一路退到文件系统根 "/"，false 停在内部存储根。
 * [repo] 只有 [FolderPickerMode.REMOTE] 才需要。
 *
 * 弹窗不管是因为选中还是返回而关闭，都会调 [onClose]，让底层弹窗能恢复显示；
 * [onPick] 只在使用「使用此文件夹」时调用，用来回填路径。
 */
data class FolderPickerLauncher(
    val mode: FolderPickerMode,
    val repo: RemoteRepo? = null,
    val initialPath: String = "",
    val allowRoot: Boolean = false,
    val onPick: (String) -> Unit,
    val onClose: () -> Unit = {}
)
