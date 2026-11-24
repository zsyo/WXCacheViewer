package top.aico.wxcacheviewer.domain.emoji

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * GIF89a 编码器：把若干 ARGB 帧写成一张无限循环的动图。
 *
 * 为什么要自己写：Android 只带 GIF **解码**，没有任何编码入口（`ImageIO` 也不在 SDK 里），
 * 而表情导出要的就是一个到处都能打开的动图。实现按规范做两件事——
 * 中位切分（median cut）挑 256 色全局调色板，再走标准 GIF LZW 压缩。
 *
 * 纯 Kotlin、不碰 `android.*`，单测可以真编码、再用 `javax.imageio` 解回来逐帧校验。
 */
object GifEncoder {

    /** GIF 全局色表最大 256 色。 */
    private const val PALETTE_SIZE = 256

    /** 挑调色板时最多采样这么多个像素，再多也没意义。 */
    private const val SAMPLE_LIMIT = 32_768

    private const val MAX_CODE = 4096
    private const val MAX_CODE_SIZE = 12

    /** 调色板量化用的通道位数：5 位 → 32 级查色表。跟 GIF 的码长无关，别混用。 */
    private const val QUANT_BITS = 5
    private const val LEVELS = 1 shl QUANT_BITS

    /** LZW 最小码长：256 色调色板对应 8 位，基表 256 项 + clear + EOI。 */
    private const val COLOR_BITS = 8

    /**
     * 编码成 GIF。[frames] 每帧长度必须等于 `width * height`，元素是 ARGB。
     *
     * [frameDelayMs] 不足 10ms 时按 1 厘秒处理——GIF 的时间单位是 1/100 秒。
     */
    fun encode(
        width: Int,
        height: Int,
        frames: List<IntArray>,
        frameDelayMs: Int,
        out: OutputStream
    ) {
        require(width > 0 && height > 0 && frames.isNotEmpty()) { "空的动图" }
        require(frames.all { it.size == width * height }) { "帧尺寸不一致" }

        val palette = paletteOf(frames)
        val indexer = Indexer(palette)

        out.write(MAGIC)
        writeU16(out, width)
        writeU16(out, height)
        // 有全局色表、颜色分辨率 8 位、色表大小 2^8
        out.write(0xF7)
        out.write(0x00)
        out.write(0x00)
        palette.forEach { color ->
            out.write((color shr 16) and 0xFF)
            out.write((color shr 8) and 0xFF)
            out.write(color and 0xFF)
        }
        writeLoopExtension(out)

        val delay = (frameDelayMs / 10).coerceAtLeast(1)
        frames.forEach { frame ->
            writeGraphicControl(out, delay)
            writeImageDescriptor(out, width, height)
            val compressed = ByteArrayOutputStream(frame.size / 2 + 64)
            writeLzw(indexer.index(frame), compressed)
            out.write(COLOR_BITS)                 // LZW 最小码长
            writeSubBlocks(out, compressed.toByteArray())
        }
        out.write(TRAILER)
        out.flush()
    }

    // ---- GIF 结构 ----

    private fun writeLoopExtension(out: OutputStream) {
        out.write(0x21)
        out.write(0xFF)
        out.write(0x0B)
        out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(0x03)
        out.write(0x01)
        writeU16(out, 0)                          // 0 = 无限循环
        out.write(0x00)
    }

    /** 图形控制扩展：disposal 取 1（保留画面），不使用透明色。 */
    private fun writeGraphicControl(out: OutputStream, delay: Int) {
        out.write(0x21)
        out.write(0xF9)
        out.write(0x04)
        out.write(0x04)
        writeU16(out, delay)
        out.write(0x00)
        out.write(0x00)
    }

    private fun writeImageDescriptor(out: OutputStream, width: Int, height: Int) {
        out.write(0x2C)
        writeU16(out, 0)
        writeU16(out, 0)
        writeU16(out, width)
        writeU16(out, height)
        out.write(0x00)                           // 无局部色表、非隔行
    }

    private fun writeSubBlocks(out: OutputStream, data: ByteArray) {
        var offset = 0
        while (offset < data.size) {
            val size = minOf(255, data.size - offset)
            out.write(size)
            out.write(data, offset, size)
            offset += size
        }
        out.write(0x00)
    }

    private fun writeU16(out: OutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
    }

    // ---- 调色板 ----

    /**
     * 中位切分挑调色板：不断的把「颜色跨度最大」的盒子按最长通道的中位数切成两半，
     * 切到 256 个盒子为止，每个盒子的平均色就是一个调色板颜色。
     *
     * 表情动图色块平、帧间差异小，这套朴素做法足够，不需要抖动。
     */
    private fun paletteOf(frames: List<IntArray>): IntArray {
        val colors = samplePixels(frames)
        if (colors.isEmpty()) return IntArray(PALETTE_SIZE)

        val boxes = ArrayList<Box>()
        boxes += Box(0, colors.size)
        while (boxes.size < PALETTE_SIZE) {
            var target = -1
            // 跨度 0 的盒子（单色）再切也是同一个颜色，留着只会白跑几百轮
            var widest = 0
            boxes.forEachIndexed { index, box ->
                if (box.length <= 1) return@forEachIndexed
                val spread = spread(colors, box)
                if (spread > widest) {
                    widest = spread
                    target = index
                }
            }
            if (target < 0) break
            val box = boxes[target]
            sortRange(colors, box, widestChannel(colors, box))
            val middle = box.from + box.length / 2
            boxes[target] = Box(box.from, middle)
            boxes += Box(middle, box.to)
        }

        val palette = IntArray(PALETTE_SIZE)
        boxes.forEachIndexed { index, box -> palette[index] = average(colors, box) }
        // 盒子数不到 256 时用最后一个颜色补齐，避免后面的索引指到黑色
        val filler = boxes.lastOrNull()?.let { average(colors, it) } ?: 0
        for (index in boxes.size until PALETTE_SIZE) palette[index] = filler
        return palette
    }

    private fun samplePixels(frames: List<IntArray>): IntArray {
        val total = frames.sumOf { it.size }
        val step = maxOf(1, total / SAMPLE_LIMIT)
        val out = IntArray((total + step - 1) / step)
        var count = 0
        frames.forEach { frame ->
            var index = 0
            while (index < frame.size && count < out.size) {
                out[count++] = frame[index]
                index += step
            }
        }
        return if (count == out.size) out else out.copyOf(count)
    }

    private class Box(val from: Int, val to: Int) {
        val length: Int get() = to - from
    }

    private fun channelValue(color: Int, channel: Int): Int = when (channel) {
        0 -> (color shr 16) and 0xFF
        1 -> (color shr 8) and 0xFF
        else -> color and 0xFF
    }

    /** 盒子在各通道上的跨度，取最大的那个作为切分依据。 */
    private fun spread(colors: IntArray, box: Box): Int {
        var widest = 0
        for (channel in 0 until 3) {
            var low = 255
            var high = 0
            for (index in box.from until box.to) {
                val value = channelValue(colors[index], channel)
                if (value < low) low = value
                if (value > high) high = value
            }
            if (high - low > widest) widest = high - low
        }
        return widest
    }

    private fun widestChannel(colors: IntArray, box: Box): Int {
        var channel = 0
        var widest = -1
        for (candidate in 0 until 3) {
            var low = 255
            var high = 0
            for (index in box.from until box.to) {
                val value = channelValue(colors[index], candidate)
                if (value < low) low = value
                if (value > high) high = value
            }
            if (high - low > widest) {
                widest = high - low
                channel = candidate
            }
        }
        return channel
    }

    private fun sortRange(colors: IntArray, box: Box, channel: Int) {
        val sorted = colors.copyOfRange(box.from, box.to).toList()
            .sortedBy { channelValue(it, channel) }
        var index = box.from
        for (color in sorted) colors[index++] = color
    }

    private fun average(colors: IntArray, box: Box): Int {
        if (box.length <= 0) return 0
        var red = 0L
        var green = 0L
        var blue = 0L
        for (index in box.from until box.to) {
            val color = colors[index]
            red += (color shr 16) and 0xFF
            green += (color shr 8) and 0xFF
            blue += color and 0xFF
        }
        val count = box.length
        return ((red / count).toInt() shl 16) or ((green / count).toInt() shl 8) or
            (blue / count).toInt()
    }

    /**
     * 像素 → 调色板索引。
     *
     * 逐像素线性比 256 色太慢（320×320×63 帧会到亿级），这里先建一张
     * 32×32×32 的查色表：每个「5 位量化色」预先算出最近的调色板索引，
     * 之后每像素只查一次表。
     */
    private class Indexer(private val palette: IntArray) {

        private val table = IntArray(LEVELS * LEVELS * LEVELS)

        init {
            for (r in 0 until LEVELS) {
                for (g in 0 until LEVELS) {
                    for (b in 0 until LEVELS) {
                        table[(r shl 10) or (g shl 5) or b] =
                            nearest(expand(r), expand(g), expand(b))
                    }
                }
            }
        }

        fun index(frame: IntArray): ByteArray {
            val out = ByteArray(frame.size)
            for (index in frame.indices) {
                val color = frame[index]
                val cell = (((color shr 19) and 0x1F) shl 10) or
                    (((color shr 11) and 0x1F) shl 5) or
                    ((color shr 3) and 0x1F)
                out[index] = table[cell].toByte()
            }
            return out
        }

        private fun nearest(red: Int, green: Int, blue: Int): Int {
            var best = 0
            var bestDistance = Int.MAX_VALUE
            for (index in palette.indices) {
                val color = palette[index]
                val dr = red - ((color shr 16) and 0xFF)
                val dg = green - ((color shr 8) and 0xFF)
                val db = blue - (color and 0xFF)
                val distance = dr * dr + dg * dg + db * db
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = index
                }
            }
            return best
        }
    }

    private fun expand(value: Int): Int = (value shl 3) or (value shr 2)

    // ---- LZW ----

    /**
     * GIF 版 LZW：码长从 `COLOR_BITS + 1` 起，字典满 4096 条时发 clear 重置。
     *
     * 字典用一张 8192 槽的开放寻址哈希表（键 = 前缀码 << 8 | 新字节），
     * 比 `HashMap` 少一层装箱，编码几十万像素也不会成瓶颈。
     */
    private fun writeLzw(indices: ByteArray, out: OutputStream) {
        val clearCode = 1 shl COLOR_BITS
        val endCode = clearCode + 1
        val writer = BitWriter(out)
        var codeSize = COLOR_BITS + 1
        var next = endCode + 1

        val keys = IntArray(TABLE_SIZE) { -1 }
        val values = IntArray(TABLE_SIZE)

        writer.write(clearCode, codeSize)
        if (indices.isEmpty()) {
            writer.write(endCode, codeSize)
            writer.flush()
            return
        }
        var prefix = indices[0].toInt() and 0xFF
        var index = 1
        while (index < indices.size) {
            val value = indices[index].toInt() and 0xFF
            val key = (prefix shl 8) or value
            val slot = slotOf(keys, key)
            if (keys[slot] == key) {
                prefix = values[slot]
                index++
                continue
            }
            writer.write(prefix, codeSize)
            if (next < MAX_CODE) {
                keys[slot] = key
                values[slot] = next
                next++
                // 解码器读完一个码才补一条表项，比编码器慢一步：码长必须等
                // 「下一个空码号越过 2^码长」再扩，早一步就会整段解错
                if (next > (1 shl codeSize) && codeSize < MAX_CODE_SIZE) codeSize++
            } else {
                writer.write(clearCode, codeSize)
                keys.fill(-1)
                next = endCode + 1
                codeSize = COLOR_BITS + 1
            }
            prefix = value
            index++
        }
        writer.write(prefix, codeSize)
        writer.write(endCode, codeSize)
        writer.flush()
    }

    private fun slotOf(keys: IntArray, key: Int): Int {
        val mask = keys.size - 1
        var slot = ((key * HASH_MULTIPLIER) ushr 16) and mask
        while (true) {
            val existing = keys[slot]
            if (existing == -1 || existing == key) return slot
            slot = (slot + 1) and mask
        }
    }

    /** GIF 的码流按低位先拼，凑满一字节就吐出去。 */
    private class BitWriter(private val out: OutputStream) {
        private var buffer = 0
        private var bits = 0

        fun write(code: Int, size: Int) {
            buffer = buffer or (code shl bits)
            bits += size
            while (bits >= 8) {
                out.write(buffer and 0xFF)
                buffer = buffer ushr 8
                bits -= 8
            }
        }

        fun flush() {
            if (bits > 0) {
                out.write(buffer and 0xFF)
                buffer = 0
                bits = 0
            }
        }
    }

    private const val TABLE_SIZE = 8192
    private const val HASH_MULTIPLIER = 0x9E3779B1.toInt()

    private val MAGIC = "GIF89a".toByteArray(Charsets.US_ASCII)
    private const val TRAILER = 0x3B
}
