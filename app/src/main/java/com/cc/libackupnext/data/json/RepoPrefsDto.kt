@file:OptIn(ExperimentalSerializationApi::class)

package com.cc.libackupnext.data.json

import com.cc.libackupnext.data.RemoteRepo
import com.cc.libackupnext.data.RepoAuth
import com.cc.libackupnext.data.RepoProtocol
import com.cc.libackupnext.data.SavedRepo
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * SharedPreferences 里 remote_repos / saved_repos 两个清单的 JSON 结构。
 *
 * 协议 / 登录方式在 JSON 里存的是 `key`（webdav / public_key …），不是枚举名，
 * 所以这里保留 String，由 [com.cc.libackupnext.data.RepoProtocol.of] 等业务侧解析，
 * 连「老配置没有 protocol 字段时按 WebDAV 解析」的兜底也一并保留。
 */

@Serializable
data class RemoteRepoDto(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val id: String = "",
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val name: String = "",
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val protocol: String = "",
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val auth: String = "",
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val host: String = "",
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val user: String = "",
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val password: String = "",
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("private_key") val privateKey: String = "",
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val path: String = "",
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val insecure: Boolean = false
)

@Serializable
data class SavedRepoDto(
    val id: String = "",
    val name: String = "",
    val location: String = "",
    val password: String = "",
    val remote: RemoteRepoDto? = null
)

// ==================== 业务模型 <-> 清单 DTO ====================

fun RemoteRepo.toDto(): RemoteRepoDto = RemoteRepoDto(
    id = id,
    name = name,
    protocol = protocol.key,
    auth = auth.key,
    host = host,
    user = user,
    password = password,
    privateKey = privateKey,
    path = path,
    insecure = insecure
)

/** 用 of() 兜底：老配置没有 protocol / auth 字段时按 WebDAV + 密码解析。 */
fun RemoteRepoDto.toModel(): RemoteRepo = RemoteRepo(
    id = id,
    name = name,
    protocol = RepoProtocol.of(protocol),
    auth = RepoAuth.of(auth),
    host = host,
    user = user,
    password = password,
    privateKey = privateKey,
    path = path,
    insecure = insecure
)

fun SavedRepo.toDto(): SavedRepoDto = SavedRepoDto(
    id = id,
    name = name,
    location = location,
    password = password,
    remote = remote?.toDto()
)

fun SavedRepoDto.toModel(): SavedRepo = SavedRepo(
    id = id,
    name = name,
    location = location,
    password = password,
    remote = remote?.toModel()
)
