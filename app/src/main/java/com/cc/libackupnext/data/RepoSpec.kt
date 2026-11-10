package com.cc.libackupnext.data

import org.json.JSONObject

/**
 * 仓库服务协议：本地和三种远程协议在同一个下拉里选，配置窗口只有一个。
 * [defaultName] 是「名称」字段的默认值：本地显示中文，远程用小写协议名。
 */
enum class RepoProtocol(val key: String, val label: String) {
    LOCAL("local", "本地"),
    WEBDAV("webdav", "WebDAV"),
    SFTP("sftp", "SFTP"),
    SMB("smb", "SMB");

    val defaultName: String get() = if (this == LOCAL) "本地" else key

    val isLocal: Boolean get() = this == LOCAL

    /** 该协议支持的登录方式；本地没有认证方式，返回空列表让 UI 不显示这一行。 */
    fun authOptions(): List<RepoAuth> = when (this) {
        LOCAL -> emptyList()
        WEBDAV -> listOf(RepoAuth.PASSWORD)
        SFTP -> listOf(RepoAuth.PASSWORD, RepoAuth.PUBLIC_KEY)
        SMB -> listOf(RepoAuth.PASSWORD, RepoAuth.GUEST, RepoAuth.ANONYMOUS)
    }

    companion object {
        // 老配置里没有 protocol 字段时按远程 WebDAV 解析，别把已保存的服务变成本地
        fun of(key: String): RepoProtocol = entries.firstOrNull { it.key == key } ?: WEBDAV
    }
}

/** 登录方式。不是所有协议都支持全部选项，可用项由 [RepoProtocol.authOptions] 决定。 */
enum class RepoAuth(val key: String, val label: String) {
    PASSWORD("password", "密码"),
    PUBLIC_KEY("public_key", "公钥"),
    GUEST("guest", "来宾"),
    ANONYMOUS("anonymous", "匿名");

    companion object {
        fun of(key: String): RepoAuth = entries.firstOrNull { it.key == key } ?: PASSWORD
    }
}

/**
 * 一份仓库配置（本地 / 远程共用）。用户只填协议 / 名称 / 地址 / 认证方式 / 凭据 / 路径，
 * 仓库位置（location）和后端选项由这里与 service 层派生，界面上不出现。
 *
 * 本地协议的 [path] 就是文件系统目录；远程协议的 [path] 是服务里的相对路径。
 */
data class RemoteRepo(
    val id: String = System.currentTimeMillis().toString(),
    val name: String = RepoProtocol.LOCAL.defaultName,
    val protocol: RepoProtocol = RepoProtocol.LOCAL,
    val auth: RepoAuth = RepoAuth.PASSWORD,
    val host: String = "",
    val user: String = "",
    val password: String = "",
    val privateKey: String = "",
    val path: String = "",
    /** WebDAV：允许明文 http 和自签名 / 过期证书（跳过 TLS 校验）。 */
    val insecure: Boolean = false
) {
    /** 本地直接是目录路径；远程用逻辑标识，Rust 侧据此起 restic 服务。 */
    val location: String
        get() = if (protocol.isLocal) path else "remote:$id:${path.trimStart('/')}"

    /** 展示 / 日志用，绝不带密码或私钥。 */
    fun redacted(): String = if (protocol.isLocal) "本地 · $path" else
        "${protocol.label} · ${name.ifBlank { host }}"

    /** 老配置 / 切协议后可能留下当前协议不支持的登录方式，一律回落到该协议第一项。 */
    fun effectiveAuth(): RepoAuth {
        val options = protocol.authOptions()
        return if (auth in options) auth else options.firstOrNull() ?: RepoAuth.PASSWORD
    }

    /** 按协议判断必填项；本地只要目录，远程还要地址与对应凭据。 */
    fun isComplete(): Boolean {
        if (protocol.isLocal) return path.isNotBlank()
        if (name.isBlank() || host.isBlank()) return false
        return when (effectiveAuth()) {
            RepoAuth.PASSWORD -> user.isNotBlank() && password.isNotBlank()
            RepoAuth.PUBLIC_KEY -> user.isNotBlank() && privateKey.isNotBlank()
            RepoAuth.GUEST, RepoAuth.ANONYMOUS -> true
        }
    }

    /**
     * 初始化 / 保存仓库的前置条件：远程必须先选过仓库路径，
     * 否则空路径会把整个服务根当成仓库。测连接和路径浏览仍只看 [isComplete]。
     */
    fun canInit(): Boolean = isComplete() && (protocol.isLocal || path.isNotBlank())

    /** 切换协议：清空输入框、重置认证方式，名称没被用户改过时跟着协议名走。 */
    fun switchedTo(next: RepoProtocol): RemoteRepo {
        if (next == protocol) return this
        val keepName = name.isNotBlank() && name != protocol.defaultName
        return RemoteRepo(
            id = id,
            name = if (keepName) name else next.defaultName,
            protocol = next,
            auth = next.authOptions().firstOrNull() ?: RepoAuth.PASSWORD
        )
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("protocol", protocol.key)
        put("auth", auth.key)
        put("host", host)
        put("user", user)
        put("password", password)
        put("private_key", privateKey)
        put("path", path)
        put("insecure", insecure)
    }

    companion object {
        fun fromJson(o: JSONObject): RemoteRepo = RemoteRepo(
            id = o.optString("id"),
            name = o.optString("name"),
            protocol = RepoProtocol.of(o.optString("protocol")),
            auth = RepoAuth.of(o.optString("auth")),
            host = o.optString("host"),
            user = o.optString("user"),
            password = o.optString("password"),
            privateKey = o.optString("private_key"),
            path = o.optString("path"),
            insecure = o.optBoolean("insecure", false)
        )
    }
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
