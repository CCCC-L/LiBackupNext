@file:OptIn(ExperimentalSerializationApi::class)

package com.cc.libackupnext.data.json

import com.cc.libackupnext.data.RemoteRepo
import com.cc.libackupnext.data.SavedRepo
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * SharedPreferences 里 remote_repos / saved_repos 两个清单的 JSON 结构。
 *
 * 协议相关的字段（host / user / password / insecure …）统一放 [RemoteRepoDto.values]，
 * 键由各协议文件声明，所以新增协议不用动这个 DTO。
 * 协议 / 登录方式在 JSON 里存的是 `key`（webdav / public_key …），不是枚举名。
 */

@Serializable
data class RemoteRepoDto(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val id: String = "",
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val name: String = "",
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val protocol: String = "",
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val path: String = "",
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val values: Map<String, String> = emptyMap()
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
    protocol = protocolKey,
    path = path,
    values = values
)

fun RemoteRepoDto.toModel(): RemoteRepo = RemoteRepo(
    id = id,
    name = name,
    protocolKey = protocol,
    path = path,
    values = values
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
