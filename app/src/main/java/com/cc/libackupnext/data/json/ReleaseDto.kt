package com.cc.libackupnext.data.json

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** GitHub releases/latest 响应里用到的几个字段；其余字段由 AppJson 忽略。 */
@Serializable
data class GithubReleaseDto(
    @SerialName("tag_name") val tagName: String = "",
    val body: String? = null,
    @SerialName("html_url") val htmlUrl: String = "",
    val assets: List<GithubAssetDto> = emptyList()
)

@Serializable
data class GithubAssetDto(
    val name: String = "",
    @SerialName("browser_download_url") val downloadUrl: String = ""
)
