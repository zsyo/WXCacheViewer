package top.aico.wxcacheviewer.domain.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceRegistryTest {

    private val md5 = "9213f8de4b8e6b5534fd84c15ddc358d"

    @Test
    fun emojiAcceptsPlainTextVariantsOnly() {
        val emoji = SourceRegistry.EMOJI
        assertTrue(SourceRegistry.isValidRelativePath(emoji, "${md5}_gif"))
        assertTrue(SourceRegistry.isValidRelativePath(emoji, "${md5}_cover"))
        assertTrue(SourceRegistry.isValidRelativePath(emoji, "${md5}_thumb"))
        // 裸 md5 是加密文件，不解码也不展示
        assertFalse(SourceRegistry.isValidRelativePath(emoji, md5))
        assertFalse(SourceRegistry.isValidRelativePath(emoji, "${md5}_gif/../x"))
        assertFalse(SourceRegistry.isValidRelativePath(emoji, "../${md5}_gif"))
    }

    @Test
    fun chatVideoAcceptsTimestampMp4Only() {
        val source = SourceRegistry.CHAT_VIDEO
        assertTrue(SourceRegistry.isValidRelativePath(source, "2609251653228091.mp4"))
        assertFalse(SourceRegistry.isValidRelativePath(source, "2609251653228091.jpg"))
        // 已失效的占位文件带 ⌖ 后缀
        assertFalse(SourceRegistry.isValidRelativePath(source, "2609272223052972.jpg⌖"))
        assertFalse(SourceRegistry.isValidRelativePath(source, "2609281024345024openim.jpg"))
    }

    @Test
    fun recursivePathsRequireHexDirectories() {
        val image = SourceRegistry.CHAT_IMAGE
        assertTrue(SourceRegistry.isValidRelativePath(image, "00/09/th_$md5"))
        assertFalse(SourceRegistry.isValidRelativePath(image, "th_$md5"))
        assertFalse(SourceRegistry.isValidRelativePath(image, "zz/09/th_$md5"))

        val sns = SourceRegistry.SNS
        assertTrue(SourceRegistry.isValidRelativePath(sns, "0/5/snst_15058803231565689446"))
        assertTrue(SourceRegistry.isValidRelativePath(sns, "1/f/snsu_15059024320059421339"))
        assertFalse(SourceRegistry.isValidRelativePath(sns, "2/2/.nomedia"))
    }

    @Test
    fun rejectsTraversalAndAbsolutePaths() {
        val source = SourceRegistry.FINDER_VIDEO
        assertFalse(SourceRegistry.isValidRelativePath(source, "/data/user/0/x"))
        assertFalse(SourceRegistry.isValidRelativePath(source, ".."))
        assertFalse(SourceRegistry.isValidRelativePath(source, "a/../b"))
        assertFalse(SourceRegistry.isValidRelativePath(source, ""))
        assertFalse(SourceRegistry.isValidRelativePath(source, "finder\\video"))
        assertFalse(SourceRegistry.isValidRelativePath(source, "a//b"))
    }

    @Test
    fun onlyReadableSourcesAllowDelete() {
        assertTrue(SourceRegistry.FINDER_VIDEO.allowDelete)
        assertTrue(SourceRegistry.CHAT_VIDEO.allowDelete)
        assertTrue(SourceRegistry.EMOJI.allowDelete)
        assertFalse(SourceRegistry.CHAT_IMAGE.allowDelete)
        assertFalse(SourceRegistry.SNS.allowDelete)

        assertTrue(SourceTab.FINDER.allowsDelete)
        assertFalse(SourceTab.IMAGES.allowsDelete)
    }

    @Test
    fun tabsCoverEverySource() {
        val covered = SourceTab.entries.flatMap { it.sources }.map { it.id }.toSet()
        assertEquals(SourceRegistry.all.map { it.id }.toSet(), covered)
        assertEquals(SourceRegistry.FINDER_VIDEO, SourceRegistry.byId("finder_video"))
        assertEquals(SourceRegistry.FINDER_VIDEO, SourceRegistry.byId("unknown"))
    }
}
