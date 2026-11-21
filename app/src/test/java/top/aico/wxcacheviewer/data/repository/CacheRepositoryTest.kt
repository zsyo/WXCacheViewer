package top.aico.wxcacheviewer.data.repository

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.aico.wxcacheviewer.data.root.RootCommandExecutor
import top.aico.wxcacheviewer.data.root.ShellResult
import top.aico.wxcacheviewer.domain.model.FileType
import top.aico.wxcacheviewer.domain.source.SourceRegistry

class CacheRepositoryTest {

    private val hash = "ffdef473a3a56cbf6d38456a41f52f46"
    private val header = "000000206674797069736f6d0000020069736f6d69736f32617663316d703431"

    private class FakeExecutor(private vararg val results: ShellResult) : RootCommandExecutor() {
        val commands = mutableListOf<String>()

        override suspend fun run(command: String, timeoutMs: Long): ShellResult {
            commands += command
            return results[minOf(commands.size - 1, results.size - 1)]
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
        assertEquals(FileType.MP4, files[0].type)
        assertEquals(4490777L, files[0].size)
        assertEquals(1795000001L, files[0].modifiedAt)
        assertTrue(files[0].name.startsWith("finder_video_xWT101"))
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
        val thumb = "th_9213f8de4b8e6b5534fd84c15ddc358d"
        val stdout = listOf(
            "$thumb\t1024\t1795000004\t-\t00/09/$thumb\t0",
            // 文件名与相对路径的末段不一致，视为不可信输出
            "$thumb\t1024\t1795000004\t-\t01/0a/th_00000000000000000000000000000000\t0"
        ).joinToString("\n")
        val files = CacheRepository(FakeExecutor(ShellResult(stdout, 0, "")))
            .listFiles(hash, listOf(SourceRegistry.CHAT_IMAGE)).files

        assertEquals(1, files.size)
        assertEquals("00/09/$thumb", files[0].relativePath)
        assertEquals(FileType.JPEG, files[0].type)
    }

    @Test
    fun recursiveListingKeepsNewestItemsOnly() = runBlocking {
        val stdout = (1..2).joinToString("\n") { index ->
            "th_9213f8de4b8e6b5534fd84c15ddc358$index\t1\t${1795000000 + index}\t-\t0$index/09/th_9213f8de4b8e6b5534fd84c15ddc358$index\t0"
        }
        val listing = CacheRepository(FakeExecutor(ShellResult(stdout, 0, "")))
            .listFiles(hash, listOf(SourceRegistry.CHAT_IMAGE))

        assertEquals(2, listing.files.size)
        assertTrue(listing.files[0].modifiedAt > listing.files[1].modifiedAt)
        assertTrue(!listing.truncated)
    }
}
