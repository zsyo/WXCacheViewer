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
        assertTrue(command.contains("stat -c %Y"))
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

    @Test
    fun copyFinderAccountDbCommandCopiesWalAndMarksMissing() {
        val command = WxPaths.copyFinderAccountDbCommand(hash, "/data/user/0/top.aico/x")
        assertTrue(command.contains("'${WxPaths.MICROMSG_ROOT}/$hash/${WxPaths.FINDER_ACCOUNT_DB}'"))
        assertTrue(command.contains("MISSING"))
        assertTrue(command.contains("-wal"))
        assertTrue(command.contains("-shm"))
        // 只改文件权限，不能用 -R 把目录的执行位也抹掉。
        assertTrue(command.contains("chmod 0666 '/data/user/0/top.aico/x/${WxPaths.FINDER_ACCOUNT_DB}'"))
        assertFalse(command.contains("chmod -R"))
        assertTrue(command.endsWith("echo OK"))
    }

    @Test
    fun copyFinderAccountDbCommandRejectsInvalidHash() {
        try {
            WxPaths.copyFinderAccountDbCommand("$hash;id", "/tmp/x")
            org.junit.Assert.fail("invalid hash should throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Invalid account hash"))
        }
    }

    @Test
    fun readLoginPrefsCommandOnlyGrepsTwoFields() {
        val command = WxPaths.readLoginPrefsCommand()
        val lines = command.lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("grep -o "))
        assertTrue(lines[0].contains("last_login_nick_name"))
        assertTrue(lines[1].contains("last_login_uin"))
        assertTrue(command.contains("'${WxPaths.LOGIN_PREFS_FILE}'"))
    }
}
