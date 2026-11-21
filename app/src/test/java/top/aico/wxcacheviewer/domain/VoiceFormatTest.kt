package top.aico.wxcacheviewer.domain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceFormatTest {

    private val silkHeader = "#!SILK_V3".toByteArray(Charsets.US_ASCII)
    private val amrHeader = "#!AMR".toByteArray(Charsets.US_ASCII)

    /** 造一段「标准 SILK 头 + 两帧」的字节流，帧头是 2 字节小端长度。 */
    private fun silkStream(vararg frames: ByteArray): ByteArray {
        val bytes = silkHeader.toMutableList()
        frames.forEach { frame ->
            bytes += (frame.size and 0xFF).toByte()
            bytes += ((frame.size shr 8) and 0xFF).toByte()
            bytes += frame.toList()
        }
        return bytes.toByteArray()
    }

    @Test
    fun estimatesDurationFromFrameCount() {
        val stream = silkStream(ByteArray(10), ByteArray(12), ByteArray(14))
        // 每帧 20ms，三帧 60ms
        assertEquals(60L, VoiceFormat.durationMillisOfStandardSilk(stream))
        // 结尾的 0xFF 0xFF 不算一帧
        val terminated = stream + byteArrayOf(0xFF.toByte(), 0xFF.toByte())
        assertEquals(60L, VoiceFormat.durationMillisOfStandardSilk(terminated))
        // 不是 SILK 时不猜
        assertEquals(0L, VoiceFormat.durationMillisOfStandardSilk(amrHeader))
        Unit
    }

    @Test
    fun recognisesWeixinPrivateHeader() {
        val body = silkStream(byteArrayOf(1, 2, 3))
        val weixin = byteArrayOf(0x02) + body
        assertEquals(VoiceFormat.Kind.WEIXIN_SILK, VoiceFormat.kindOf(weixin))
        assertEquals(VoiceFormat.Kind.SILK, VoiceFormat.kindOf(body))
        assertEquals(VoiceFormat.Kind.AMR, VoiceFormat.kindOf(amrHeader + byteArrayOf(1, 2)))
        assertEquals(VoiceFormat.Kind.UNKNOWN, VoiceFormat.kindOf(byteArrayOf(1, 2, 3, 4)))
        assertEquals(VoiceFormat.Kind.UNKNOWN, VoiceFormat.kindOf(ByteArray(0)))
    }

    @Test
    fun stripsOnlyTheWeixinMagicByte() {
        val body = silkStream(byteArrayOf(9, 9, 9))
        assertArrayEquals(body, VoiceFormat.toStandardSilk(byteArrayOf(0x02) + body))
        // 已经是标准格式时原样返回
        assertArrayEquals(body, VoiceFormat.toStandardSilk(body))
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesToTreatAmrAsSilk() {
        VoiceFormat.toStandardSilk(amrHeader + byteArrayOf(1, 2))
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesUnknownBytes() {
        VoiceFormat.toStandardSilk(byteArrayOf(0x00, 0x01))
    }
}
