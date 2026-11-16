package com.cc.libackupnext.data.protocol

import com.cc.libackupnext.data.RemoteRepo

/**
 * 仓库服务协议的抽象：一个协议一个文件，注册进 [RepoProtocols.all] 就能用。
 *
 * 协议文件负责三件事，UI 与后端都不再认识具体协议：
 * 1. [fields] 声明表单要哪些字段（顺序即渲染顺序，随当前值变化）；
 * 2. [rcloneOptions] 把字段映射成 rclone inline 配置里除 type / _root 之外的键；
 * 3. [address] 给列表页 / 日志一个不带密码的可读地址。
 *
 * 字段值统一存在 [RemoteRepo.values] 里，键由协议自己声明（见各协议文件里的常量）。
 */
interface RepoProtocol {

    /** 存储键，同时是 rclone 的 backend type。 */
    val key: String

    /** 协议下拉里显示的名字。 */
    val label: String

    /** 本地协议不生成 rclone 远程配置，目录浏览也走 local 后端。 */
    val isLocal: Boolean get() = false

    /** 支持的登录方式；空列表表示不需要认证（本地）。 */
    val auths: List<RepoAuthOption> get() = emptyList()

    /** 「名称」字段的默认值：本地显示中文，远程用小写协议名。 */
    val defaultName: String get() = if (isLocal) label else key

    /** 表单字段，顺序即渲染顺序；占位符、凭据显隐都在这里按当前值算。 */
    fun fields(repo: RemoteRepo): List<RepoField>

    /** rclone inline 配置里除 type / _root 外的键；空 map 表示不参与远程配置。 */
    fun rcloneOptions(repo: RemoteRepo): Map<String, String>

    /** 列表 / 日志里的可读地址，绝不带密码或私钥。 */
    fun address(repo: RemoteRepo): String

    /** 当前生效的登录方式：存的 key 不被支持时回落到该协议第一项。 */
    fun effectiveAuth(repo: RemoteRepo): RepoAuthOption? =
        auths.firstOrNull { it.key == repo[FIELD_AUTH] } ?: auths.firstOrNull()

    /** 必填校验：默认看 [fields] 里标了 required 的字段。 */
    fun isComplete(repo: RemoteRepo): Boolean =
        fields(repo).all { !it.required || repo[it.id].isNotBlank() }

    /** 展示 / 日志用，绝不带密码或私钥。 */
    fun describe(repo: RemoteRepo): String =
        if (isLocal) "$label · ${repo.path}"
        else "$label · ${repo.name.ifBlank { repo[FIELD_HOST] }}"
}

/** 登录方式选项。key 存进 [RemoteRepo.values]，label 给下拉显示。 */
data class RepoAuthOption(val key: String, val label: String)

/** 认证方式在 [RemoteRepo.values] 里的固定键：所有带认证的协议共用。 */
const val FIELD_AUTH = "auth"

/** 地址 / 主机名在 [RemoteRepo.values] 里的通用键，各协议复用同一个表单字段。 */
const val FIELD_HOST = "host"

/** 表单字段描述：协议只声明「要什么」，具体控件由 UI 按类型渲染。 */
sealed interface RepoField {

    /** 存储键，同时也是 [RemoteRepo] 取值用的键。 */
    val id: String

    val label: String

    /** 参与 [RepoProtocol.isComplete] 校验；开关 / 下拉一般不标。 */
    val required: Boolean get() = false

    /** 单行或多行文本；[secret] 用密码框渲染，值不进日志。 */
    data class Text(
        override val id: String,
        override val label: String,
        val placeholder: String = "",
        val secret: Boolean = false,
        val multiline: Boolean = false,
        override val required: Boolean = false
    ) : RepoField

    /** 下拉选择，例如认证方式。 */
    data class Select(
        override val id: String,
        override val label: String,
        val options: List<RepoAuthOption>,
        override val required: Boolean = false
    ) : RepoField

    /** 带说明的开关，例如 WebDAV 的「允许不安全的服务」。 */
    data class Toggle(
        override val id: String,
        override val label: String,
        val hint: String
    ) : RepoField
}

/**
 * 协议注册表：新增协议时只需新建一个协议文件，再往 [all] 里加一行。
 *
 * 顺序即协议下拉的显示顺序。
 */
object RepoProtocols {

    val all: List<RepoProtocol> = listOf(
        LocalProtocol,
        WebDavProtocol,
        SftpProtocol,
        SmbProtocol
    )

    /** 按存储键取协议；不认识的 key 回落到占位协议，能显示、能删，但不可用。 */
    fun of(key: String): RepoProtocol = all.firstOrNull { it.key == key } ?: MissingProtocol(key)
}

/** 协议文件被删掉 / 配置来自不认识的版本：能显示、能删，但不可用。 */
private class MissingProtocol(override val key: String) : RepoProtocol {

    override val label: String get() = key

    override fun fields(repo: RemoteRepo): List<RepoField> = emptyList()

    override fun rcloneOptions(repo: RemoteRepo): Map<String, String> = emptyMap()

    override fun address(repo: RemoteRepo): String = "$key://"

    override fun isComplete(repo: RemoteRepo): Boolean = false
}

// ==================== 协议文件共用的纯函数 ====================

/**
 * `host:port` / `scheme://host:port/path` → (host, port)，端口缺省用 [defaultPort]。
 *
 * 用户可能把 `sftp://192.168.1.10:22` 整段粘进来，这里统一剥掉 scheme 和路径。
 */
internal fun splitHostPort(value: String, defaultPort: String): Pair<String, String> {
    val raw = value.trim().substringAfter("://").substringBefore('/')
    val port = raw.substringAfterLast(':', "")
    val hostOnly = if (port.isBlank()) raw else raw.substringBeforeLast(':')
    return hostOnly to port.ifBlank { defaultPort }
}

/** rclone 的 key_pem 要求单行、换行写成字面 \n，内部再 Unquote 还原成真换行。 */
internal fun singleLinePem(value: String): String = value.replace("\r\n", "\n")
    .replace('\r', '\n')
    .trim()
    .replace("\n", "\\n")

/** 把仓库路径拼到地址后面：远程路径是服务内的相对路径，空路径就不拼。 */
internal fun withPath(base: String, repo: RemoteRepo): String {
    val path = repo.path.trim().trimStart('/')
    return if (path.isBlank()) base else "${base.trimEnd('/')}/$path"
}

/** 地址里剥掉用户可能顺手输入的 scheme，再补当前协议自己的 scheme。 */
internal fun stripScheme(value: String): String = value.trim().trimEnd('/').substringAfter("://")
