package top.aico.wxcacheviewer.data.repository

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.aico.wxcacheviewer.data.root.RootCommandExecutor
import top.aico.wxcacheviewer.data.root.ShellResult
import top.aico.wxcacheviewer.domain.model.CacheFile
import top.aico.wxcacheviewer.domain.model.FileType
import top.aico.wxcacheviewer.domain.source.SourceRegistry
import java.io.File

class CacheRepositoryTest {

    private val hash = "ffdef473a3a56cbf6d38456a41f52f46"
    private val header = "000000206674797069736f6d0000020069736f6d69736f32617663316d703431"
    private val thumb = "th_9213f8de4b8e6b5534fd84c15ddc358d"

    private class FakeExecutor(private vararg val results: ShellResult) : RootCommandExecutor() {
        val commands = mutableListOf<String>()

        override suspend fun run(command: String, timeoutMs: Long): ShellResult {
            commands += command
            return results[minOf(commands.size - 1, results.size - 1)]
        }
    }

    /** 按命令内容分派响应，用于覆盖「列子目录 → 扫单目录」的分页流程。 */
    private class RoutingExecutor(private val handler: (String) -> ShellResult) :
        RootCommandExecutor() {
        val commands = mutableListOf<String>()

        override suspend fun run(command: String, timeoutMs: Long): ShellResult {
            commands += command
            return handler(command)
        }
    }

    private fun pageLine(relativePath: String, mtime: Long, size: Long = 1024): String {
        val name = relativePath.substringAfterLast('/')
        return "$name\t$size\t$mtime\t-\t$relativePath\t0"
    }

    /** 只有一个子目录 `00`、内容固定的递归源执行器。 */
    private fun singleDirExecutor(pageStdout: String) = RoutingExecutor { command ->
        when {
            command.contains("stat -c '%Y %n'") -> ShellResult("1795000000 00/", 0, "")
            command.contains("find ") -> ShellResult(pageStdout, 0, "")
            else -> ShellResult("", 0, "")
        }
    }

    @Test
    fun parsesLinesAndIgnoresInvalidNames() = runBlocking {
        val stdout = listOf(
            "finder_video_xWT101_0651b1fbc2d34e5f8a6b7c8d9e0f1e19\t4490777\t1795000001\t$header",
            "finder_video__9213f8de4b8e6b5534fd84c15ddc358d\t6847763\t1795000002\t$header",
            "evil\t1\t0\t$header"
        ).joinToString("\n")
        val files = CacheRepository(FakeExecutor(ShellResult(stdout, 0, ""))).listFiles(hash)

        assertEquals(2, files.size)
        // 页内按修改时间倒序，因此不按 shell 输出顺序断言
        val newest = files.first()
        assertEquals(FileType.MP4, newest.type)
        assertEquals(6847763L, newest.size)
        assertEquals(1795000002L, newest.modifiedAt)
        assertEquals(4490777L, files.last().size)
        assertTrue(files.any { it.name.startsWith("finder_video_xWT101") })
    }

    @Test
    fun fallsBackToWcWhenStatUnsupported() = runBlocking {
        val executor = FakeExecutor(
            ShellResult("", 1, "stat: unrecognized option"),
            ShellResult(
                "finder_video__9213f8de4b8e6b5534fd84c15ddc358d\t6847763\t1795000002\t$header\n",
                0,
                ""
            )
        )
        val files = CacheRepository(executor).listFiles(hash)

        assertEquals(1, files.size)
        assertEquals(1795000002L, files[0].modifiedAt)
        assertEquals(2, executor.commands.size)
        assertTrue(executor.commands[1].contains("wc -c"))
    }

    @Test
    fun parsesRelativePathAndThumbnailForChatVideo() = runBlocking {
        val stdout = "2609251653228091.mp4\t1163387\t1795000003\t-\t2609251653228091.mp4\t1"
        val files = CacheRepository(FakeExecutor(ShellResult(stdout, 0, "")))
            .listFiles(hash, listOf(SourceRegistry.CHAT_VIDEO)).files

        assertEquals(1, files.size)
        assertEquals(FileType.MP4, files[0].type)
        assertTrue(files[0].hasThumbnail)
        assertEquals("chat_video", files[0].sourceId)
        assertEquals("chat_video:2609251653228091.mp4", files[0].key)
    }

    @Test
    fun keepsNestedRelativePathAndDropsMismatchedName() = runBlocking {
        val stdout = listOf(
            pageLine("00/09/$thumb", 1795000004),
            // 文件名与相对路径的末段不一致，视为不可信输出
            "$thumb\t1024\t1795000004\t-\t01/0a/th_00000000000000000000000000000000\t0"
        ).joinToString("\n")
        val files = CacheRepository(singleDirExecutor(stdout))
            .listFiles(hash, listOf(SourceRegistry.CHAT_IMAGE)).files

        assertEquals(1, files.size)
        assertEquals("00/09/$thumb", files[0].relativePath)
        assertEquals(FileType.JPEG, files[0].type)
    }

    @Test
    fun newestSubDirIsScannedFirst() = runBlocking {
        val executor = RoutingExecutor { command ->
            when {
                // 子目录按 mtime 倒序：11 更新，应排在 00 前面
                command.contains("stat -c '%Y %n'") ->
                    ShellResult("1795000000 00/\n1795000900 11/", 0, "")
                command.contains("/11'") -> ShellResult(pageLine("11/09/$thumb", 1795001000), 0, "")
                command.contains("/00'") -> ShellResult(pageLine("00/09/$thumb", 1795000100), 0, "")
                else -> ShellResult("", 0, "")
            }
        }
        val repo = CacheRepository(executor)

        val first = repo.loadPage(hash, listOf(SourceRegistry.CHAT_IMAGE), null)
        assertEquals("11/09/$thumb", first.files.single().relativePath)
        assertNotNull(first.nextCursor)

        val second = repo.loadPage(hash, listOf(SourceRegistry.CHAT_IMAGE), first.nextCursor)
        assertEquals("00/09/$thumb", second.files.single().relativePath)
        assertNull(second.nextCursor)
    }

    @Test
    fun firstPageFailureIsReportedWithTimeoutMessage() = runBlocking {
        val executor = RoutingExecutor { command ->
            when {
                command.contains("stat -c '%Y %n'") -> ShellResult("1795000000 00/", 0, "")
                command.contains("find ") -> ShellResult("", -1, "命令超时", timedOut = true)
                else -> ShellResult("", 0, "")
            }
        }
        val page = CacheRepository(executor)
            .loadPage(hash, listOf(SourceRegistry.CHAT_IMAGE), null)

        assertTrue(page.files.isEmpty())
        assertNull(page.nextCursor)
        assertTrue(page.error!!.contains("超时"))
    }

    @Test
    fun emptySubDirIsSkippedAutomatically() = runBlocking {
        val executor = RoutingExecutor { command ->
            when {
                command.contains("stat -c '%Y %n'") ->
                    ShellResult("1795000900 11/\n1795000000 00/", 0, "")
                command.contains("/11'") -> ShellResult("", 0, "")
                command.contains("/00'") -> ShellResult(pageLine("00/09/$thumb", 1795000100), 0, "")
                else -> ShellResult("", 0, "")
            }
        }
        val page = CacheRepository(executor).loadPage(hash, listOf(SourceRegistry.CHAT_IMAGE), null)

        assertEquals("00/09/$thumb", page.files.single().relativePath)
        assertNull(page.nextCursor)
    }

    @Test
    fun unparsablePageIsReportedInsteadOfTreatedAsEmptyDir() = runBlocking {
        // 真实踩过的坑：shell 侧写成 `while IFS= read -r sz mt path`，IFS 被置空后
        // 整行全落进第一列，输出退化成「文件名和路径都是空、中间一列挤成一团」。
        // 这种页面不能被当成空目录跳过，否则界面只会显示「该账号没有此类型的缓存」。
        val broken =
            "\t6004 1790336752 /data/user/0/com.tencent.mm/MicroMsg/$hash/image2/00/09/$thumb\t\t-\t\t0"
        val page = CacheRepository(singleDirExecutor(broken))
            .loadPage(hash, listOf(SourceRegistry.CHAT_IMAGE), null)

        assertTrue(page.files.isEmpty())
        assertEquals("文件列表格式异常", page.error)
    }

    /** 聊天视频的一行六字段输出。 */
    private fun videoLine(name: String, mtime: Long): String =
        "$name\t1024\t$mtime\t-\t$name\t0"

    @Test
    fun flatSourcePagesByOffset() = runBlocking {
        // 页大小压到 2，方便用三行数据覆盖「满页 → 有下一页」和「不满页 → 到底」
        val small = SourceRegistry.CHAT_VIDEO.copy(pageSize = 2)
        val executor = RoutingExecutor { command ->
            when {
                command.contains("-le 0 ]") -> ShellResult(
                    listOf(
                        videoLine("2609251653228091.mp4", 1795000001),
                        videoLine("2609251653228092.mp4", 1795000002)
                    ).joinToString("\n"),
                    0,
                    ""
                )
                else -> ShellResult(videoLine("2609251653228093.mp4", 1795000003), 0, "")
            }
        }
        val repo = CacheRepository(executor)

        val first = repo.loadPage(hash, listOf(small), null)
        assertEquals(2, first.files.size)
        assertEquals(PageCursor(0, 2), first.nextCursor)

        val second = repo.loadPage(hash, listOf(small), first.nextCursor)
        assertEquals(1, second.files.size)
        assertNull(second.nextCursor)
    }

    @Test
    fun timeoutAdvancesCursorOnlyByLinesActuallyRead() = runBlocking {
        // 超时只输出了 2 行就中断；游标必须推进 2 而不是整页 5，
        // 否则中间 3 个文件在本次会话里永远翻不到。
        val small = SourceRegistry.CHAT_VIDEO.copy(pageSize = 5)
        val executor = RoutingExecutor { command ->
            ShellResult(
                listOf(
                    videoLine("2609251653228091.mp4", 1795000001),
                    videoLine("2609251653228092.mp4", 1795000002)
                ).joinToString("\n"),
                -1,
                "命令超时",
                timedOut = true
            )
        }
        val page = CacheRepository(executor).loadPage(hash, listOf(small), null)

        assertEquals(2, page.files.size)
        assertEquals(PageCursor(0, 2), page.nextCursor)
        assertTrue(page.error!!.contains("超时"))
    }

    @Test
    fun flatSourcePageUsesSourcePageSize() = runBlocking {
        val executor = FakeExecutor(ShellResult("", 0, ""))
        CacheRepository(executor).loadPage(hash, listOf(SourceRegistry.EMOJI), null)

        assertEquals(1, executor.commands.size)
        // 默认一页 60 个；命令里体现为「跳过 0 个、超过 60 个就 break」
        assertTrue(executor.commands[0].contains("-le 0 ]"))
        assertTrue(executor.commands[0].contains("-gt 60 ]"))
    }

    @Test
    fun fallsBackToDirNamesWhenStatLacksNamePlaceholder() = runBlocking {
        val executor = RoutingExecutor { command ->
            when {
                // 模拟 stat 不支持 %n：一行都解析不出来
                command.contains("stat -c '%Y %n'") -> ShellResult("", 0, "")
                command.contains("ls -1") -> ShellResult("00\n01\nbroken name\n", 0, "")
                command.contains("/00'") -> ShellResult(pageLine("00/09/$thumb", 1795000100), 0, "")
                else -> ShellResult("", 0, "")
            }
        }
        val page = CacheRepository(executor).loadPage(hash, listOf(SourceRegistry.CHAT_IMAGE), null)

        assertEquals(1, page.files.size)
        assertNull(page.error)
    }

    @Test
    fun parsesChatFileTypeFromExtension() = runBlocking {
        val stdout = listOf(
            "项目资料 归档.zip\t4158664\t1795000010\t-\t项目资料 归档.zip\t0",
            "团队协作流程说明.pdf\t1483595\t1795000011\t-\t团队协作流程说明.pdf\t0",
            "示例文档.docx\t95232\t1795000012\t-\t示例文档.docx\t0",
            "da_1700000000000\t5778613\t1795000013\t-\tda_1700000000000\t0"
        ).joinToString("\n")
        val files = CacheRepository(FakeExecutor(ShellResult(stdout, 0, "")))
            .listFiles(hash, listOf(SourceRegistry.CHAT_FILE)).files

        assertEquals(4, files.size)
        // 页内按时间倒序：最新的是无扩展名的那个
        assertEquals(FileType.UNKNOWN, files.first().type)
        assertTrue(files.any { it.type == FileType.DOCX })
        assertTrue(files.any { it.type == FileType.PDF })
        assertTrue(files.any { it.type == FileType.ZIP })
        // 空格与中文都要原样保留，不能被当成路径分隔或截断
        assertTrue(files.any { it.name == "项目资料 归档.zip" })
        assertEquals(
            "chat_file:项目资料 归档.zip",
            files.first { it.name == "项目资料 归档.zip" }.key
        )
    }

    @Test
    fun exportKeepsOriginalNameAndAvoidsOverwrite() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "wxcache-export-${System.nanoTime()}")
        dir.mkdirs()
        // 目标目录已有同名文件时改成「名字(1).docx」，不能覆盖用户之前导出的
        File(dir, "示例文档.docx").writeText("old")
        val file = CacheFile(
            accountHash = hash,
            name = "示例文档.docx",
            size = 95232,
            modifiedAt = 1795000012,
            type = FileType.DOCX,
            relativePath = "示例文档.docx",
            sourceId = "chat_file"
        )
        val executor = FakeExecutor(ShellResult("OK", 0, ""))
        val exported = CacheRepository(executor).exportFile(file, dir)

        assertEquals("示例文档(1).docx", exported.name)
        assertTrue(executor.commands.single().contains("示例文档(1).docx"))
        dir.deleteRecursively()
        Unit
    }
}
