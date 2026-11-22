package top.aico.wxcacheviewer.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExportLocationTest {

    @Test
    fun resolvesPrimaryVolumePath() {
        assertEquals(
            "/storage/emulated/0/Download/WXCacheViewer",
            ExportLocation.resolve("primary:Download/WXCacheViewer")
        )
    }

    @Test
    fun resolvesSecondaryVolumePath() {
        assertEquals(
            "/storage/1234-5678/导出/微信",
            ExportLocation.resolve("1234-5678:导出/微信")
        )
    }

    @Test
    fun rejectsVolumeRoot() {
        assertNull(ExportLocation.resolve("primary:"))
        assertNull(ExportLocation.resolve("primary"))
    }

    @Test
    fun rejectsIllegalSegments() {
        assertNull(ExportLocation.resolve("primary:Download/../Android"))
        assertNull(ExportLocation.resolve("primary:Download//WXCacheViewer"))
    }

    @Test
    fun rejectsIllegalVolume() {
        // volume 会拼进 /storage/$volume，含点或斜杠会造成路径遍历
        assertNull(ExportLocation.resolve("..:Download/WXCacheViewer"))
        assertNull(ExportLocation.resolve("../etc:Download"))
        assertNull(ExportLocation.resolve("a/b:Download"))
        assertNull(ExportLocation.resolve(":Download"))
    }

    @Test
    fun displayNameUsesLastSegment() {
        assertEquals("WXCacheViewer", ExportLocation.displayName("/storage/emulated/0/Download/WXCacheViewer/"))
        assertEquals("Download", ExportLocation.displayName("/storage/emulated/0/Download"))
    }
}
