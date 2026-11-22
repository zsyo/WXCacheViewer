package top.aico.wxcacheviewer.domain.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticVersionTest {

    private fun version(raw: String): SemanticVersion =
        requireNotNull(SemanticVersion.parse(raw)) { "无法解析 $raw" }

    @Test
    fun parsesTagWithVPrefixAndBuildMetadata() {
        val parsed = version("v1.2.3+build.9")
        assertEquals(1, parsed.major)
        assertEquals(2, parsed.minor)
        assertEquals(3, parsed.patch)
        assertTrue(parsed.preRelease.isEmpty())
        assertEquals("1.2.3", parsed.toString())
    }

    @Test
    fun fillsMissingPatchWithZero() {
        val parsed = version("0.1")
        assertEquals(0, parsed.major)
        assertEquals(1, parsed.minor)
        assertEquals(0, parsed.patch)
    }

    @Test
    fun rejectsUnparsableVersion() {
        // 手动触发构建的 versionName 是 dev-<sha>，不参与比较
        assertNull(SemanticVersion.parse("dev-1a2b3c4"))
        assertNull(SemanticVersion.parse(""))
        assertNull(SemanticVersion.parse("1.2.3.4"))
        assertNull(SemanticVersion.parse("1.x"))
    }

    @Test
    fun comparesNumericPartsByValue() {
        assertTrue(version("0.10.0") > version("0.9.9"))
        assertTrue(version("2.0.0") > version("1.99.99"))
        assertEquals(0, version("v1.0.0").compareTo(version("1.0.0")))
    }

    @Test
    fun prereleaseIsOlderThanSameRelease() {
        assertTrue(version("1.0.0-beta.2") < version("1.0.0"))
        // 数字段按数值比较，beta.10 比 beta.9 新
        assertTrue(version("1.0.0-beta.10") > version("1.0.0-beta.9"))
        // 数字段优先级低于字母段
        assertTrue(version("1.0.0-1") < version("1.0.0-alpha"))
        // 相同前缀时，段数多的更新
        assertTrue(version("1.0.0-beta.1") > version("1.0.0-beta"))
    }
}
