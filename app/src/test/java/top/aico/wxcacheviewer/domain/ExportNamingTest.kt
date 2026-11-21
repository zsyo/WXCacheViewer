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
        dir.deleteRecursively()
        Unit
    }
}
