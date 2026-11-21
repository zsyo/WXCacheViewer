package top.aico.wxcacheviewer.data.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.aico.wxcacheviewer.domain.source.SourceRegistry

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

    @Test
    fun chatVideoListCommandProbesJpgInsteadOfHeader() {
        val command = WxPaths.listSourceFilesCommand(hash, SourceRegistry.CHAT_VIDEO)
        assertTrue(command.contains("'${WxPaths.MICROMSG_ROOT}/$hash/video'/*.mp4"))
        assertTrue(command.contains("\${f%.mp4}.jpg"))
        assertFalse(command.contains("head -c 32"))
    }

    @Test
    fun recursiveSourceListsSubDirsThenOneDirPerPage() {
        val subDirs = WxPaths.listSourceSubDirsCommand(hash, SourceRegistry.CHAT_IMAGE)
        assertTrue(subDirs.contains("D='${WxPaths.MICROMSG_ROOT}/$hash/image2'"))
        assertTrue(subDirs.contains("stat -c '%Y %n' \"\$D\"/*/"))

        val page = WxPaths.listSourceDirPageCommand(hash, SourceRegistry.CHAT_IMAGE, "0a")
        assertTrue(page.contains("P='${WxPaths.MICROMSG_ROOT}/$hash/image2/0a'"))
        // 从一级子目录往里扫，深度比源本身少一层
        assertTrue(page.contains("find \"\$P\" -maxdepth 3 -type f"))
        assertTrue(page.contains("-name 'th_*'"))
        assertTrue(page.contains("tr '\\n' '\\0'"))
        assertTrue(page.contains("xargs -0 -n 200 stat -c '%s %Y %n'"))
        assertTrue(page.contains("head -n 400"))
        assertFalse(page.contains("-printf"))
        // 不能逐文件 fork stat
        assertFalse(page.contains("\$(stat"))
        // read 多变量时不能写成 `IFS= read`：那会把 IFS 置空、禁用字段拆分，
        // 整行全落进第一个变量，六列输出会退化成「只有中间一列有内容」。
        assertTrue(page.contains("while read -r sz mt path"))
        assertFalse(page.contains("IFS= read -r sz"))
    }

    @Test
    fun flatSourcePageCommandSlicesByOffset() {
        val page = WxPaths.listSourcePageCommand(hash, SourceRegistry.CHAT_VIDEO, 120, 60)

        // 只对「本页」这几十个文件 stat，前面用 shell 内建计数跳过，不 fork
        assertTrue(page.contains("ls -1r \"\$D\""))
        assertTrue(page.contains("case \"\$n\" in *.mp4) ;; *) continue ;; esac"))
        assertTrue(page.contains("if [ \"\$i\" -le 120 ]; then continue; fi"))
        assertTrue(page.contains("if [ \"\$i\" -gt 180 ]; then break; fi"))
        // 不能再对整个目录做 for 展开再逐个 stat
        assertFalse(page.contains("for f in "))
        // 尺寸要作为命令替换执行，不能把表达式当字面量打进输出
        assertTrue(page.contains("\$(stat -c %s"))
    }

    @Test
    fun flatSourcePageCommandKeepsThumbnailProbe() {
        val page = WxPaths.listSourcePageCommand(hash, SourceRegistry.CHAT_VIDEO, 0, 60)
        assertTrue(page.contains("\${f%.mp4}.jpg"))
    }

    @Test
    fun flatSourcePageRejectsBadArguments() {
        try {
            WxPaths.listSourcePageCommand(hash, SourceRegistry.CHAT_VIDEO, -1, 60)
            org.junit.Assert.fail("negative offset should throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("offset"))
        }
        try {
            WxPaths.listSourcePageCommand(hash, SourceRegistry.CHAT_IMAGE, 0, 60)
            org.junit.Assert.fail("recursive source should be paged by dir")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("paged by dir"))
        }
    }

    @Test
    fun pagedCommandsRejectUnsafeDir() {
        try {
            WxPaths.listSourceDirPageCommand(hash, SourceRegistry.CHAT_IMAGE, "../evil")
            org.junit.Assert.fail("unsafe dir should throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Invalid dir name"))
        }
    }

    @Test
    fun flatSourceCannotBePaged() {
        try {
            WxPaths.listSourceDirPageCommand(hash, SourceRegistry.CHAT_VIDEO, "00")
            org.junit.Assert.fail("flat source should throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("cannot be paged"))
        }
    }

    @Test
    fun sourceFilePathRejectsTraversal() {
        assertEquals(
            "${WxPaths.MICROMSG_ROOT}/$hash/emoji/9213f8de4b8e6b5534fd84c15ddc358d_gif",
            WxPaths.sourceFilePath(hash, SourceRegistry.EMOJI, "9213f8de4b8e6b5534fd84c15ddc358d_gif")
        )
        try {
            WxPaths.sourceFilePath(hash, SourceRegistry.EMOJI, "../../evil_gif")
            org.junit.Assert.fail("traversal should throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Invalid relative path"))
        }
    }

    @Test
    fun deleteRejectsReadOnlySource() {
        try {
            WxPaths.deleteSourceFileCommand(
                hash,
                SourceRegistry.CHAT_IMAGE,
                "00/09/th_9213f8de4b8e6b5534fd84c15ddc358d"
            )
            org.junit.Assert.fail("read-only source should throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("does not allow delete"))
        }
    }
}
