package top.aico.wxcacheviewer.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import top.aico.wxcacheviewer.domain.model.FileType

class ChatFileTypesTest {

    @Test
    fun mapsCommonChatFileExtensions() {
        assertEquals(FileType.DOCX, ChatFileTypes.fromName("示例文档.docx"))
        assertEquals(FileType.XLSX, ChatFileTypes.fromName("台账.XLSX"))
        assertEquals(FileType.PDF, ChatFileTypes.fromName("团队协作流程.pdf"))
        assertEquals(FileType.ZIP, ChatFileTypes.fromName("项目资料 归档.zip"))
        assertEquals(FileType.APK, ChatFileTypes.fromName("app-release.apk"))
        assertEquals(FileType.AUDIO, ChatFileTypes.fromName("语音.m4a"))
        assertEquals(FileType.JPEG, ChatFileTypes.fromName("照片.jpeg"))
    }

    @Test
    fun unknownOrMissingExtensionStaysUnknown() {
        assertNull(ChatFileTypes.fromName("da_1700000000000"))
        assertNull(ChatFileTypes.fromName("no-extension"))
        assertNull(ChatFileTypes.fromName("archive.rar"))
        assertNull(ChatFileTypes.fromName(".zip"))
        assertNull(ChatFileTypes.fromName("name."))
    }

    @Test
    fun badgePrefersShortExtension() {
        assertEquals("ZIP", ChatFileTypes.badge("包.zip", FileType.ZIP))
        assertEquals("DOCX", ChatFileTypes.badge("汇报.docx", FileType.DOCX))
        // 无扩展名或扩展名过长时退回类型名
        assertEquals("文件", ChatFileTypes.badge("da_1700000000000", FileType.UNKNOWN))
        assertEquals("文件", ChatFileTypes.badge("data.verylongext", FileType.UNKNOWN))
        assertEquals("音频", ChatFileTypes.badge("voice", FileType.AUDIO))
    }
}
