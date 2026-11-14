package com.cc.libackupnext.service

import com.cc.libackupnext.BuildConfig
import com.cc.libackupnext.data.ReleaseBlock
import com.cc.libackupnext.data.ReleaseInfo
import com.cc.libackupnext.data.json.GithubReleaseDto
import com.cc.libackupnext.service.json.parseJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

private const val REPO = "CCCC-L/LiBackupNext"
private const val LATEST_API = "https://api.github.com/repos/$REPO/releases/latest"
private const val RELEASE_PAGE = "https://github.com/$REPO/releases/latest"

/**
 * 启动时查 GitHub 最新 Release。
 *
 * 不引网络库：`HttpURLConnection` + kotlinx.serialization（无反射，正式包体积可控）。
 * 失败（无网络 / 403 限流 / 解析异常）由调用方静默忽略，不打扰用户。
 */
object UpdateChecker {

    private val HEADING_LINE = Regex("""^(#{1,6})\s+(.*)$""")
    private val BULLET_LINE = Regex("""^( *)[-*+]\s+(.*)$""")

    /** null = 已经是最新版本。 */
    suspend fun check(): Result<ReleaseInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(LATEST_API).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 10_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                // GitHub API 没有 User-Agent 会直接 403
                setRequestProperty("User-Agent", "LiBackupNext/${BuildConfig.APP_VERSION}")
            }
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    error("GitHub API 返回 ${conn.responseCode}")
                }
                val release = conn.inputStream.bufferedReader().use { it.readText() }
                    .parseJson<GithubReleaseDto>()

                // tag_name 就是打包时的 versionName（例如 1.2.1），直接比字符串
                val tag = release.tagName.trim()
                if (tag.isEmpty() || tag == BuildConfig.APP_VERSION) return@runCatching null

                ReleaseInfo(
                    version = tag,
                    notes = parseReleaseBody(release.body.orEmpty()),
                    // release 里通常还挂着 .sha256，必须按 .apk 过滤
                    downloadUrl = release.assets.firstOrNull {
                        it.name.endsWith(".apk", ignoreCase = true)
                    }?.downloadUrl?.takeIf { it.isNotBlank() }
                        ?: release.htmlUrl.ifBlank { RELEASE_PAGE }
                )
            } finally {
                conn.disconnect()
            }
        }
    }

    /**
     * release body 是 Markdown 且 GitHub 返回 CRLF。逐行拆成块，
     * 行内标记（**粗体** / `代码`）原样留给 UI 层转 SpanStyle。
     * 空行不产生块，段间距由渲染决定；认不出来的行退化成 [ReleaseBlock.Paragraph]。
     */
    private fun parseReleaseBody(raw: String): List<ReleaseBlock> = raw
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .lineSequence()
        .map { it.trimEnd() }
        .filter { it.isNotBlank() }
        .map { line ->
            val heading = HEADING_LINE.matchEntire(line)
            val bullet = BULLET_LINE.matchEntire(line)
            when {
                heading != null -> ReleaseBlock.Heading(
                    text = heading.groupValues[2].trim(),
                    level = heading.groupValues[1].length
                )

                bullet != null -> ReleaseBlock.Bullet(
                    text = bullet.groupValues[2].trim(),
                    depth = bullet.groupValues[1].length / 2
                )

                else -> ReleaseBlock.Paragraph(line.trim())
            }
        }
        .toList()
}
