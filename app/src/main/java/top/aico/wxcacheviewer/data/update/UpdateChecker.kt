package top.aico.wxcacheviewer.data.update

import org.json.JSONException
import org.json.JSONObject
import top.aico.wxcacheviewer.domain.update.ReleaseInfo
import top.aico.wxcacheviewer.domain.update.UpdateChannel
import java.io.IOException
import java.io.Reader
import java.net.HttpURLConnection
import java.net.URI

enum class UpdateError {
    /** 连不上：无网络、DNS 失败、超时。 */
    NETWORK,
    /** GitHub 限流（未认证 60 次/小时）或被拒绝。 */
    RATE_LIMIT,
    /** 其它非 2xx 状态码，[UpdateCheckException.detail] 是状态码。 */
    HTTP,
    /** 返回内容不是预期的 JSON。 */
    PARSE
}

class UpdateCheckException(
    val error: UpdateError,
    val detail: String? = null
) : Exception("update check failed: $error${detail?.let { " ($it)" }.orEmpty()}")

/**
 * 通过 GitHub Releases 检查更新。
 *
 * 只在用户点「检查更新」时调用，应用启动与后台都不会主动请求。
 * 场景只有一个 GET，用 [HttpURLConnection] 就够了，不额外引入 HTTP 客户端。
 */
class UpdateChecker(
    private val repository: String = DEFAULT_REPOSITORY,
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val apiBase: String = DEFAULT_API_BASE
) {

    /** 返回 null 表示该渠道还没有已发布的版本。 */
    fun fetchLatest(channel: UpdateChannel): ReleaseInfo? = when (channel) {
        UpdateChannel.STABLE -> fetchStable()
        UpdateChannel.PRERELEASE -> fetchNewest()
    }

    /** 正式版：只认 GitHub 的 latest，它本身就排除了预发布与草稿。 */
    private fun fetchStable(): ReleaseInfo? {
        val body = request("$apiBase/repos/$repository/releases/latest") ?: return null
        val json = try {
            JSONObject(body)
        } catch (e: JSONException) {
            throw UpdateCheckException(UpdateError.PARSE)
        }
        return ReleaseParser.parseRelease(json) ?: throw UpdateCheckException(UpdateError.PARSE)
    }

    /** 预发布版：拉最近一页，本地按版本号挑最大，避免依赖接口顺序。 */
    private fun fetchNewest(): ReleaseInfo? {
        val body = request("$apiBase/repos/$repository/releases?per_page=$PAGE_SIZE") ?: return null
        return ReleaseParser.newest(ReleaseParser.parseReleases(body))
    }

    private fun request(url: String): String? {
        val connection = try {
            URI.create(url).toURL().openConnection() as HttpURLConnection
        } catch (e: IOException) {
            throw UpdateCheckException(UpdateError.NETWORK)
        } catch (e: IllegalArgumentException) {
            throw UpdateCheckException(UpdateError.NETWORK)
        }
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            // GitHub API 要求带 UA，Accept 用官方推荐的媒体类型
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", userAgent)
            val code = connection.responseCode
            // 仓库还没有任何 Release 时 latest 返回 404，这不是错误
            if (code == HttpURLConnection.HTTP_NOT_FOUND) return null
            if (code == HTTP_FORBIDDEN || code == HTTP_TOO_MANY_REQUESTS) {
                throw UpdateCheckException(UpdateError.RATE_LIMIT)
            }
            if (code != HttpURLConnection.HTTP_OK) {
                throw UpdateCheckException(UpdateError.HTTP, code.toString())
            }
            return connection.inputStream.bufferedReader(Charsets.UTF_8)
                .use { it.readAtMost(MAX_BODY_CHARS) }
        } catch (e: UpdateCheckException) {
            throw e
        } catch (e: IOException) {
            throw UpdateCheckException(UpdateError.NETWORK)
        } finally {
            connection.disconnect()
        }
    }

    /** 响应体做上限保护，避免异常情况下把整个响应读进内存。 */
    private fun Reader.readAtMost(limit: Int): String {
        val builder = StringBuilder()
        val buffer = CharArray(8 * 1024)
        while (builder.length < limit) {
            val read = read(buffer, 0, minOf(buffer.size, limit - builder.length))
            if (read < 0) break
            builder.append(buffer, 0, read)
        }
        return builder.toString()
    }

    companion object {
        const val DEFAULT_REPOSITORY = "zsyo/WXCacheViewer"
        const val DEFAULT_API_BASE = "https://api.github.com"
        const val DEFAULT_USER_AGENT = "WXCacheViewer"
        private const val PAGE_SIZE = 20
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val MAX_BODY_CHARS = 2_000_000
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_TOO_MANY_REQUESTS = 429
    }
}
