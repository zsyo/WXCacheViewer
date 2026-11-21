package top.aico.wxcacheviewer.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import top.aico.wxcacheviewer.domain.model.FileType

class FileTypeDetectorTest {

    private data class Case(val hex: String, val expected: FileType)

    private val cases = listOf(
        Case("000000206674797069736f6d0000020069736f6d69736f32617663316d703431", FileType.MP4),
        Case("0000001c6674797069736f6d0000020069736f6d69736f326d703431", FileType.MP4),
        Case("ffd8ffe000104a46494600010100000100010000", FileType.JPEG),
        Case("89504e470d0a1a0a0000000d49484452", FileType.PNG),
        Case("47494638396101000100800000000000ffffff21f9", FileType.GIF),
        Case("5249464612345678574542505650382010000000", FileType.WEBP),
        Case("00000000", FileType.UNKNOWN),
        Case("ff", FileType.UNKNOWN),
        Case("", FileType.UNKNOWN)
    )

    @Test
    fun detectsAllKnownMagicNumbers() {
        cases.forEach { (hex, expected) ->
            assertEquals("header: $hex", expected, FileTypeDetector.detect(hex))
        }
    }

    @Test
    fun ignoresWhitespaceAndUpperCaseInHex() {
        assertEquals(FileType.MP4, FileTypeDetector.detect("00 00 00 20 66 74 79 70 69 73 6F 6D"))
    }
}
