package top.aico.wxcacheviewer.domain.emoji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream

/**
 * GIF 编码器的端到端校验：编出来的字节用 `javax.imageio` 解回去，
 * 逐帧比像素——比只断言文件头可靠得多，LZW 码长增长、字典重置这些都能覆盖到。
 */
class GifEncoderTest {

    private fun encode(
        width: Int,
        height: Int,
        frames: List<IntArray>,
        delayMs: Int = 33
    ): ByteArray {
        val out = ByteArrayOutputStream()
        GifEncoder.encode(width, height, frames, delayMs, out)
        return out.toByteArray()
    }

    private fun decode(bytes: ByteArray): List<BufferedImage> {
        val reader = ImageIO.getImageReadersByFormatName("gif").next()
        reader.input = MemoryCacheImageInputStream(ByteArrayInputStream(bytes))
        return try {
            (0 until reader.getNumImages(true)).map { reader.read(it) }
        } finally {
            reader.dispose()
        }
    }

    @Test
    fun writesHeaderWithScreenSizeAndLoopExtension() {
        val bytes = encode(width = 2, height = 3, frames = listOf(IntArray(6) { RED }))

        assertEquals("GIF89a", String(bytes, 0, 6, Charsets.US_ASCII))
        // 逻辑屏幕宽高是小端
        assertEquals(2, bytes[6].toInt() and 0xFF)
        assertEquals(0, bytes[7].toInt() and 0xFF)
        assertEquals(3, bytes[8].toInt() and 0xFF)
        assertEquals(0, bytes[9].toInt() and 0xFF)
        // 有全局色表 + 256 色
        assertEquals(0xF7, bytes[10].toInt() and 0xFF)
        assertTrue(String(bytes, Charsets.ISO_8859_1).contains("NETSCAPE2.0"))
        assertEquals(0x3B, bytes.last().toInt() and 0xFF)
    }

    @Test
    fun keepsEveryFrameAndItsColour() {
        val colors = intArrayOf(RED, GREEN, BLUE)
        val frames = colors.map { color -> IntArray(4 * 4) { color } }

        val decoded = decode(encode(4, 4, frames))

        assertEquals(3, decoded.size)
        decoded.forEachIndexed { index, image ->
            assertEquals(4, image.width)
            assertEquals(4, image.height)
            // 纯色帧切出来的调色板就是那个颜色，量化后应当完全一致
            assertEquals(colors[index] and 0xFFFFFF, image.getRGB(0, 0) and 0xFFFFFF)
        }
    }

    @Test
    fun handlesNoisyFramesWhichFillAndResetTheDictionary() {
        val random = Random(7)
        val width = 128
        val height = 128
        val frames = (0 until 3).map {
            IntArray(width * height) { 0xFF000000.toInt() or random.nextInt(0x1000000) }
        }

        val decoded = decode(encode(width, height, frames))

        assertEquals(3, decoded.size)
        // 256 色量化必然有误差，这里只要求「解出来是像样的图」，验证的是码流本身没写坏
        var error = 0L
        var samples = 0
        frames.first().forEachIndexed { index, color ->
            if (index % 37 != 0) return@forEachIndexed
            val pixel = decoded.first().getRGB(index % width, index / width)
            for (shift in intArrayOf(16, 8, 0)) {
                val expected = (color shr shift) and 0xFF
                val actual = (pixel shr shift) and 0xFF
                error += kotlin.math.abs(expected - actual)
                samples++
            }
        }
        val meanError = error.toDouble() / samples
        assertTrue("平均通道误差过大: $meanError", meanError < 24.0)
    }

    @Test
    fun writesMinimalImageWithoutCrashing() {
        val decoded = decode(encode(1, 1, listOf(IntArray(1) { GREEN })))

        assertEquals(1, decoded.size)
        assertEquals(GREEN and 0xFFFFFF, decoded.first().getRGB(0, 0) and 0xFFFFFF)
    }

    private companion object {
        const val RED = 0xFFFF0000.toInt()
        const val GREEN = 0xFF00FF00.toInt()
        const val BLUE = 0xFF0000FF.toInt()
    }
}
