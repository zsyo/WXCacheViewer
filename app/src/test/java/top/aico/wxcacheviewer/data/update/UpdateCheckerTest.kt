package top.aico.wxcacheviewer.data.update

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import top.aico.wxcacheviewer.domain.update.UpdateChannel
import java.net.InetSocketAddress

/**
 * 用本地 HTTP 服务验证请求路径、请求头、状态码映射与解析，
 * 不依赖外网，CI 上也能跑。
 */
class UpdateCheckerTest {

    @Test
    fun stableChannelAsksLatestEndpoint() {
        var path = ""
        var userAgent = ""
        withServer({ exchange ->
            path = exchange.requestURI.path
            userAgent = exchange.requestHeaders.getFirst("User-Agent").orEmpty()
            exchange.respond(200, releaseJson("v0.1.1"))
        }) { base ->
            val checker = UpdateChecker(apiBase = base, userAgent = "WXCacheViewer/0.1")
            val release = checker.fetchLatest(UpdateChannel.STABLE)
            assertEquals("v0.1.1", release?.tagName)
            assertEquals("/repos/zsyo/WXCacheViewer/releases/latest", path)
            assertEquals("WXCacheViewer/0.1", userAgent)
        }
    }

    @Test
    fun prereleaseChannelPicksNewestAcrossAllReleases() {
        var query = ""
        withServer({ exchange ->
            query = exchange.requestURI.query.orEmpty()
            exchange.respond(
                200,
                """
                [
                  ${releaseJson("v0.1.1")},
                  ${releaseJson("v0.2.0-beta.1", prerelease = true)}
                ]
                """.trimIndent()
            )
        }) { base ->
            val checker = UpdateChecker(apiBase = base)
            val release = checker.fetchLatest(UpdateChannel.PRERELEASE)
            assertEquals("v0.2.0-beta.1", release?.tagName)
            assertTrue(query.startsWith("per_page="))
        }
    }

    @Test
    fun missingReleaseReturnsNull() {
        withServer({ exchange -> exchange.respond(404, """{"message":"Not Found"}""") }) { base ->
            assertNull(UpdateChecker(apiBase = base).fetchLatest(UpdateChannel.STABLE))
        }
    }

    @Test
    fun forbiddenIsReportedAsRateLimit() {
        withServer({ exchange -> exchange.respond(403, """{"message":"rate limit"}""") }) { base ->
            val error = expectFailure { UpdateChecker(apiBase = base).fetchLatest(UpdateChannel.STABLE) }
            assertEquals(UpdateError.RATE_LIMIT, error.error)
        }
    }

    @Test
    fun otherStatusCarriesCode() {
        withServer({ exchange -> exchange.respond(500, "") }) { base ->
            val error = expectFailure { UpdateChecker(apiBase = base).fetchLatest(UpdateChannel.STABLE) }
            assertEquals(UpdateError.HTTP, error.error)
            assertEquals("500", error.detail)
        }
    }

    @Test
    fun brokenJsonIsReportedAsParseError() {
        withServer({ exchange -> exchange.respond(200, "<html>oops</html>") }) { base ->
            val error = expectFailure { UpdateChecker(apiBase = base).fetchLatest(UpdateChannel.STABLE) }
            assertEquals(UpdateError.PARSE, error.error)
        }
    }

    private fun expectFailure(block: () -> Unit): UpdateCheckException {
        try {
            block()
        } catch (e: UpdateCheckException) {
            return e
        }
        fail("预期抛出 UpdateCheckException")
        throw AssertionError("unreachable")
    }

    private fun withServer(handler: (HttpExchange) -> Unit, block: (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            try {
                handler(exchange)
            } finally {
                exchange.close()
            }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }

    private fun HttpExchange.respond(code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        sendResponseHeaders(code, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun releaseJson(tag: String, prerelease: Boolean = false): String {
        val version = tag.removePrefix("v")
        return """
            {
              "tag_name": "$tag",
              "draft": false,
              "prerelease": $prerelease,
              "published_at": "2026-11-22T08:00:00Z",
              "html_url": "https://example.com/releases/tag/$tag",
              "body": "说明",
              "assets": [
                {
                  "name": "WXCacheViewer-$version-arm64-v8a.apk",
                  "browser_download_url": "https://example.com/$version/arm64.apk"
                }
              ]
            }
        """.trimIndent()
    }
}
