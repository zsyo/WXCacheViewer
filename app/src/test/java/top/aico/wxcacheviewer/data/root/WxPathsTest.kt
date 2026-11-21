package top.aico.wxcacheviewer.data.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WxPathsTest {

    private val hash = "ffdef473a3a56cbf6d38456a41f52f46"
    private val fileName = "finder_video_xWT101_0651b1fbc2d34e5f8a6b7c8d9e0f1e19"

    @Test
    fun accountHashValidation() {
        assertTrue(WxPaths.isValidAccountHash(hash))
        assertFalse(WxPaths.isValidAccountHash(hash.uppercase()))
        assertFalse(WxPaths.isValidAccountHash("abc"))
        assertFalse(WxPaths.isValidAccountHash("$hash;id"))
    }

    @Test
    fun cacheFileNameValidation() {
        assertTrue(WxPaths.isValidCacheFileName(fileName))
        assertTrue(WxPaths.isValidCacheFileName("finder_video__9213f8de4b8e6b5534fd84c15ddc358d"))
        assertFalse(WxPaths.isValidCacheFileName("finder_video_../../evil"))
        assertFalse(WxPaths.isValidCacheFileName("other.mp4"))
    }

    @Test
    fun shellQuoteEscapesSingleQuotes() {
        assertEquals("'abc'", WxPaths.shellQuote("abc"))
        assertEquals("'a'\\''b'", WxPaths.shellQuote("a'b"))
    }

    @Test
    fun videoDirRejectsInvalidHash() {
        assertEquals(
            "${WxPaths.CACHE_ROOT}/$hash/finder/video",
            WxPaths.videoDir(hash)
        )
        try {
            WxPaths.videoDir("bad;id")
            org.junit.Assert.fail("invalid hash should throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Invalid account hash"))
        }
    }

    @Test
    fun listFilesCommandUsesQuotedPathAndHeaderProbe() {
        val command = WxPaths.listFilesCommand(hash)
        assertTrue(command.contains("head -c 32"))
        assertTrue(command.contains("od -An -v -tx1"))
        assertTrue(command.contains("stat -c %s"))
        assertTrue(command.contains("'${WxPaths.CACHE_ROOT}/$hash/finder/video'/*"))
    }

    @Test
    fun copyFileCommandRejectsMaliciousName() {
        try {
            WxPaths.copyFileCommand(hash, "../../evil", "/tmp/x.mp4")
            org.junit.Assert.fail("invalid file name should throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Invalid cache file name"))
        }
        val command = WxPaths.copyFileCommand(hash, fileName, "/tmp/x.mp4")
        assertTrue(command.startsWith("cp '"))
        assertTrue(command.contains("chmod 0644 '/tmp/x.mp4'"))
    }
}
