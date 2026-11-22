package top.aico.wxcacheviewer.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.aico.wxcacheviewer.domain.model.CacheFile
import top.aico.wxcacheviewer.domain.model.FileType
import java.io.File

class ExportNamingTest {

    private fun file(
        name: String,
        relativePath: String = name,
        sourceId: String = "voice",
        modifiedAt: Long = 1_795_000_000L
    ) = CacheFile(
        accountHash = "ffdef473a3a56cbf6d38456a41f52f46",
        name = name,
        size = 20_000,
        modifiedAt = modifiedAt,
        type = FileType.SILK,
        relativePath = relativePath,
        sourceId = sourceId
    )

    @Test
    fun voiceExportUsesTagStampAndDigest() {
        val named = ExportNaming.nameFor(
            file("msg_amr_00000000000000000000000001.amr", "03/fa/msg_amr_00000000000000000000000001.amr"),
            "mp3"
        )
        assertTrue(named.startsWith("wxvoice_"))
        assertTrue(named.endsWith(".mp3"))
        // 同一文件两次导出名字稳定，便于覆盖与排查
        assertEquals(
            named,
            ExportNaming.nameFor(
                file(
                    "msg_amr_00000000000000000000000001.amr",
                    "03/fa/msg_amr_00000000000000000000000001.amr"
                ),
                "mp3"
            )
        )
    }

    @Test
    fun chatFileSanitizesIllegalNames() {
        // preserveFileName 分支必须把路径分隔符变成下划线，并拒绝 . / .. 与空白名
        assertEquals("a_b", ExportNaming.nameFor(file("a/b", sourceId = "chat_file"), "bin"))
        assertEquals("a_b", ExportNaming.nameFor(file("a\\b", sourceId = "chat_file"), "bin"))
        assertEquals("ok", ExportNaming.nameFor(file("  ok  ", sourceId = "chat_file"), "bin"))
        // . 与 .. 非法，回退到带标签时间戳的生成名
        assertTrue(ExportNaming.nameFor(file("..", sourceId = "chat_file"), "bin").startsWith("wx"))
        assertTrue(ExportNaming.nameFor(file(".", sourceId = "chat_file"), "bin").startsWith("wx"))
    }

    @Test
    fun chatFileKeepsOriginalNameAndAvoidsOverwrite() {
        val exported = file("项目资料 归档.zip", sourceId = "chat_file", modifiedAt = 0L)
        assertEquals("项目资料 归档.zip", ExportNaming.nameFor(exported, "zip"))

        val dir = File(System.getProperty("java.io.tmpdir"), "wxnaming-${System.nanoTime()}")
        dir.mkdirs()
        File(dir, "项目资料 归档.zip").writeText("old")
        File(dir, "项目资料 归档(1).zip").writeText("old")
        assertEquals(
            "项目资料 归档(2).zip",
            ExportNaming.uniqueDestination(dir, "项目资料 归档.zip").name
        )
        check(dir.deleteRecursively())
    }
}
