package top.aico.wxcacheviewer.domain.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceRegistryTest {

    private val md5 = "9213f8de4b8e6b5534fd84c15ddc358d"

    @Test
    fun emojiAcceptsPlainTextAndEncryptedNames() {
        val emoji = SourceRegistry.EMOJI
        assertTrue(SourceRegistry.isValidRelativePath(emoji, "${md5}_gif"))
        assertTrue(SourceRegistry.isValidRelativePath(emoji, "${md5}_cover"))
        assertTrue(SourceRegistry.isValidRelativePath(emoji, "${md5}_thumb"))
        // 裸 md5 的头部是密文，解密后再判断真实类型
        assertTrue(SourceRegistry.isValidRelativePath(emoji, md5))
        assertTrue(emoji.encryptedName!!.matches(md5))
        assertFalse(emoji.encryptedName!!.matches("${md5}_gif"))
        // 与 _gif 内容相同的裸文件由 shell 跳过，避免同一张表情出现两遍
        assertEquals("_gif", emoji.skipSiblingSuffix)
        assertFalse(SourceRegistry.isValidRelativePath(emoji, "${md5}_gif/../x"))
        assertFalse(SourceRegistry.isValidRelativePath(emoji, "../${md5}_gif"))
        assertFalse(SourceRegistry.isValidRelativePath(emoji, "${md5}_other"))
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
        // 聊天文件是聊天记录的一部分，删掉聊天里就没了
        assertFalse(SourceRegistry.CHAT_FILE.allowDelete)

        assertTrue(SourceTab.FINDER.allowsDelete)
        assertFalse(SourceTab.IMAGES.allowsDelete)
        assertFalse(SourceTab.FILES.allowsDelete)
    }

    @Test
    fun chatFileAcceptsReadableNames() {
        val source = SourceRegistry.CHAT_FILE
        assertTrue(SourceRegistry.isValidRelativePath(source, "项目资料 归档.zip"))
        assertTrue(
            SourceRegistry.isValidRelativePath(
                source,
                "团队协作流程说明.pdf"
            )
        )
        // 没有扩展名的文件也会列出，界面按「文件」展示
        assertTrue(SourceRegistry.isValidRelativePath(source, "da_1700000000000"))
        assertFalse(SourceRegistry.isValidRelativePath(source, "a/b.zip"))
        assertFalse(SourceRegistry.isValidRelativePath(source, "../evil.zip"))
        assertFalse(SourceRegistry.isValidRelativePath(source, ""))
        // 制表符会破坏「六字段 tab 分隔」的列表协议
        assertFalse(SourceRegistry.isValidRelativePath(source, "a\tb.zip"))
    }

    @Test
    fun voiceAcceptsHashedAmrUnderHexDirs() {
        val voice = SourceRegistry.VOICE
        assertTrue(
            SourceRegistry.isValidRelativePath(
                voice,
                "03/fa/msg_amr_00000000000000000000000001.amr"
            )
        )
        // 个别版本只有一级子目录
        assertTrue(
            SourceRegistry.isValidRelativePath(voice, "03/msg_amr_00000000000000000000000001.amr")
        )
        // 目录必须是两位十六进制
        assertFalse(SourceRegistry.isValidRelativePath(voice, "zz/fa/msg_amr_0000.amr"))
        assertFalse(SourceRegistry.isValidRelativePath(voice, "msg_amr_0000.amr"))
        // 语音只收 .amr，且名字必须带 msg_amr_ 前缀
        assertFalse(SourceRegistry.isValidRelativePath(voice, "03/fa/msg_amr_0000.mp3"))
        assertFalse(SourceRegistry.isValidRelativePath(voice, "03/fa/other.amr"))
        assertFalse(SourceRegistry.isValidRelativePath(voice, "03/fa/../../evil.amr"))
    }

    @Test
    fun voiceTabSitsBeforeFilesAndIsReadOnly() {
        assertFalse(SourceRegistry.VOICE.allowDelete)
        assertFalse(SourceTab.VOICE.allowsDelete)
        assertTrue(SourceTab.VOICE.listOnly)
        assertEquals(listOf(SourceRegistry.VOICE), SourceTab.VOICE.sources)
        // 语音排在「文件」前面
        assertTrue(
            SourceTab.entries.indexOf(SourceTab.VOICE) <
                SourceTab.entries.indexOf(SourceTab.FILES)
        )
    }

    @Test
    fun tabsCoverEverySource() {
        val covered = SourceTab.entries.flatMap { it.sources }.map { it.id }.toSet()
        assertEquals(SourceRegistry.all.map { it.id }.toSet(), covered)
        assertEquals(SourceRegistry.FINDER_VIDEO, SourceRegistry.byId("finder_video"))
        assertEquals(SourceRegistry.FINDER_VIDEO, SourceRegistry.byId("unknown"))
    }
}
