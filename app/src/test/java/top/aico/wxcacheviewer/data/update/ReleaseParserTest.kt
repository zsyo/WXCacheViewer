package top.aico.wxcacheviewer.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import top.aico.wxcacheviewer.domain.update.ReleaseInfo
import top.aico.wxcacheviewer.domain.update.SemanticVersion

class ReleaseParserTest {

    private val releaseArray = """
        [
          {
            "tag_name": "v0.1.0",
            "draft": false,
            "prerelease": false,
            "published_at": "2026-11-01T10:00:00Z",
            "html_url": "https://example.com/releases/tag/v0.1.0",
            "body": "首个版本",
            "assets": []
          },
          {
            "tag_name": "v0.2.0-beta.1",
            "draft": false,
            "prerelease": true,
            "published_at": "2026-11-20T10:00:00Z",
            "html_url": "https://example.com/releases/tag/v0.2.0-beta.1",
            "body": "  beta 说明  ",
            "assets": [
              {
                "name": "WXCacheViewer-0.2.0-beta.1-arm64-v8a.apk",
                "browser_download_url": "https://example.com/download/arm64-v8a.apk"
              }
            ]
          },
          {
            "tag_name": "v0.3.0",
            "draft": true,
            "prerelease": false,
            "published_at": "2026-11-21T10:00:00Z",
            "html_url": "https://example.com/releases/tag/v0.3.0",
            "body": "草稿",
            "assets": []
          }
        ]
    """.trimIndent()

    @Test
    fun parsesReleaseFields() {
        val releases = ReleaseParser.parseReleases(releaseArray)
        // 草稿不参与，剩下两条
        assertEquals(2, releases.size)

        val beta = releases.first { it.prerelease }
        assertEquals("v0.2.0-beta.1", beta.tagName)
        assertEquals("2026-11-20", beta.publishedAt)
        assertEquals("beta 说明", beta.notes)
        assertEquals(1, beta.assets.size)
        assertEquals(
            "https://example.com/download/arm64-v8a.apk",
            beta.assets.first().downloadUrl
        )
    }

    @Test
    fun newestSkipsDraftAndPicksHighestVersion() {
        val newest = ReleaseParser.newest(ReleaseParser.parseReleases(releaseArray))
        assertEquals("v0.2.0-beta.1", newest?.tagName)
    }

    @Test
    fun releaseBeatsPrereleaseOfSameCore() {
        val releases = listOf(
            release("v0.2.0-rc.1", prerelease = true),
            release("v0.2.0", prerelease = false)
        )
        assertEquals("v0.2.0", ReleaseParser.newest(releases)?.tagName)
    }

    @Test
    fun skipsTagThatIsNotAVersion() {
        val body = """
            [
              {"tag_name": "nightly", "draft": false, "prerelease": false},
              {"tag_name": "v0.1.1", "draft": false, "prerelease": false}
            ]
        """.trimIndent()
        val releases = ReleaseParser.parseReleases(body)
        assertEquals(1, releases.size)
        assertEquals("v0.1.1", releases.first().tagName)
    }

    @Test
    fun returnsNullWhenNothingPublished() {
        assertNull(ReleaseParser.newest(emptyList()))
    }

    private fun release(tag: String, prerelease: Boolean) = ReleaseInfo(
        tagName = tag,
        version = requireNotNull(SemanticVersion.parse(tag)),
        prerelease = prerelease,
        publishedAt = "2026-11-20",
        htmlUrl = "https://example.com/releases/tag/$tag",
        notes = ""
    )
}
