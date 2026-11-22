package top.aico.wxcacheviewer.domain.update

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseInfoTest {

    private val release = ReleaseInfo(
        tagName = "v0.2.0",
        version = requireNotNull(SemanticVersion.parse("v0.2.0")),
        prerelease = false,
        publishedAt = "2026-11-20",
        htmlUrl = "https://example.com/releases/tag/v0.2.0",
        notes = "",
        assets = listOf(
            ReleaseAsset("WXCacheViewer-0.2.0-arm64-v8a.apk", "https://example.com/arm64.apk"),
            ReleaseAsset("WXCacheViewer-0.2.0-armeabi-v7a.apk", "https://example.com/v7a.apk"),
            ReleaseAsset("WXCacheViewer-0.2.0-x86_64.apk", "https://example.com/x86_64.apk")
        )
    )

    @Test
    fun picksAssetMatchingFirstSupportedAbi() {
        assertEquals(
            "https://example.com/arm64.apk",
            release.downloadUrlFor(listOf("arm64-v8a", "armeabi-v7a"))
        )
        assertEquals(
            "https://example.com/x86_64.apk",
            release.downloadUrlFor(listOf("x86_64"))
        )
    }

    @Test
    fun fallsBackToReleasePageWhenAbiMissing() {
        assertEquals(
            "https://example.com/releases/tag/v0.2.0",
            release.downloadUrlFor(listOf("x86"))
        )
    }
}
