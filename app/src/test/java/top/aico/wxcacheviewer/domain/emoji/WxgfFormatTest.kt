package top.aico.wxcacheviewer.domain.emoji

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WxgfFormatTest {

    private val startCode = byteArrayOf(0, 0, 0, 1)

    private fun u16(value: Int) = byteArrayOf((value ushr 8).toByte(), value.toByte())

    private fun u32(value: Int) = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte()
    )

    /**
     * 一个 NAL：起始码 + 2 字节头（类型 + layer 0 / tid 1）+ 载荷。
     *
     * [firstSlice] 对应图像 NAL 的 `first_slice_segment_in_pic_flag`（载荷首字节最高位），
     * 决定它是不是新一帧的首个分片。
     */
    private fun nal(type: Int, payload: ByteArray, firstSlice: Boolean = true): ByteArray {
        val body = payload.copyOf()
        if (firstSlice && type < 32 && body.isNotEmpty()) {
            body[0] = (body[0].toInt() or 0x80).toByte()
        }
        return startCode + byteArrayOf((type shl 1).toByte(), 0x01) + body
    }

    private fun header(
        width: Int = 320,
        height: Int = 320,
        headerLength: Int = 18,
        delayUnit: Int = 0
    ): ByteArray {
        val tail = ByteArray(headerLength - MIN_HEADER) { it.toByte() }
        // 头长度 19/20 在偏移 17 写统一帧时长（单位 2ms）；21 起后移到 18，写的是帧率
        when {
            headerLength == 19 || headerLength == 20 ->
                tail[DEFAULT_DELAY_OFFSET - MIN_HEADER] = delayUnit.toByte()
            headerLength >= 21 ->
                tail[FPS_OFFSET - MIN_HEADER] = delayUnit.toByte()
        }
        return "wxgf".toByteArray() +
            byteArrayOf(headerLength.toByte()) +
            u16(0) +
            u16(width) +
            u16(height) +
            tail
    }

    /** 分区 = 4 字节大端长度 + 码流本体，长度只算起始码之后的部分。 */
    private fun partition(body: ByteArray): ByteArray = u32(body.size) + body

    /** 分区之间的私有块：块头 2 字节是这一帧的时长，其余字节固定（真实文件里是帧头与填充）。 */
    private fun block(delayMs: Int): ByteArray = u16(delayMs) + ByteArray(22) { 0x55 }

    private fun join(vararg units: ByteArray): ByteArray =
        units.fold(ByteArray(0)) { acc, unit -> acc + unit }

    private fun parameterSets(): Triple<ByteArray, ByteArray, ByteArray> = Triple(
        nal(32, ByteArray(22) { 0x11 }),
        nal(33, ByteArray(37) { 0x22 }),
        nal(34, ByteArray(4) { 0x33 })
    )

    @Test
    fun parsesHeaderAndPartitions() {
        val (vps, sps, pps) = parameterSets()
        val first = join(vps, sps, pps, nal(19, ByteArray(64) { 0x44 }))
        val second = nal(1, ByteArray(32) { 0x55 })
        val bytes = header() + partition(first) + partition(second)

        val container = WxgfFormat.parse(bytes)!!

        assertEquals(320, container.width)
        assertEquals(320, container.height)
        assertEquals(2, container.frames.size)
        assertArrayEquals(first, container.frames[0])
        assertArrayEquals(second, container.frames[1])
        // csd 就是 VPS/SPS/PPS 首尾相接，起始码原样保留
        assertArrayEquals(join(vps, sps, pps), container.parameterSets)
        // 两个分区里各有一帧：参数集跟着第一帧，第二帧单独成 AU
        assertEquals(2, container.units.size)
    }

    @Test
    fun splitsAccessUnitsPerPicture() {
        val (vps, sps, pps) = parameterSets()
        val idr = nal(19, ByteArray(16) { 0x44 })
        val second = nal(1, ByteArray(8) { 0x55 })
        val third = nal(1, ByteArray(8) { 0x66 })
        // 一个分区里装了三帧：不切开就当一帧喂，解码器会整段丢掉
        val bytes = header() + partition(join(vps, sps, pps, idr, second, third))

        val units = WxgfFormat.parse(bytes)!!.units

        assertEquals(3, units.size)
        assertArrayEquals(join(vps, sps, pps, idr), units[0])
        assertArrayEquals(second, units[1])
        assertArrayEquals(third, units[2])
    }

    @Test
    fun keepsSlicesOfOnePictureTogether() {
        val (vps, sps, pps) = parameterSets()
        val idr = nal(19, ByteArray(16) { 0x44 })
        val secondSlice = nal(19, ByteArray(8) { 0x55 }, firstSlice = false)
        val next = nal(1, ByteArray(8) { 0x66 })

        val units = WxgfFormat.splitAccessUnits(join(vps, sps, pps, idr, secondSlice, next))

        assertEquals(2, units.size)
        assertArrayEquals(join(vps, sps, pps, idr, secondSlice), units[0])
        assertArrayEquals(next, units[1])
    }

    @Test
    fun parameterSetsAfterPictureStartNewAccessUnit() {
        val (vps, sps, pps) = parameterSets()
        val idr = nal(19, ByteArray(8) { 0x44 })
        val slice = nal(1, ByteArray(8) { 0x55 })

        val units = WxgfFormat.splitAccessUnits(join(vps, sps, pps, idr, vps, slice))

        assertEquals(2, units.size)
        assertArrayEquals(join(vps, slice), units[1])
    }

    @Test
    fun sampleDataOfAccessUnitKeepsEverySlice() {
        val first = nal(19, ByteArray(6) { 0x44 })
        val second = nal(19, ByteArray(4) { 0x55 }, firstSlice = false)

        val sample = WxgfFormat.toSampleData(join(first, second))

        val firstBody = first.copyOfRange(startCode.size, first.size)
        val secondBody = second.copyOfRange(startCode.size, second.size)
        assertArrayEquals(
            byteArrayOf(0, 0, 0, firstBody.size.toByte()) + firstBody +
                byteArrayOf(0, 0, 0, secondBody.size.toByte()) + secondBody,
            sample
        )
    }

    @Test
    fun readsPerFrameDelayFromPrivateBlocks() {
        val (vps, sps, pps) = parameterSets()
        val first = join(vps, sps, pps, nal(19, ByteArray(16) { 0x44 }))
        // 第二个分区装两帧，紧跟其后的两个私有块分别写 50ms 与 100ms
        val second = join(nal(1, ByteArray(8) { 0x55 }), nal(1, ByteArray(8) { 0x66 }))
        val bytes = header() +
            partition(first) +
            block(50) + block(100) +
            partition(second)

        val container = WxgfFormat.parse(bytes)!!

        assertEquals(3, container.units.size)
        // 分区 0 前面没有私有块，按默认 50ms；后两帧各用自己的时长
        assertArrayEquals(intArrayOf(50, 50, 100), container.frameDelaysMs)
    }

    @Test
    fun zeroDelayMeansDefaultFiftyMilliseconds() {
        val (vps, sps, pps) = parameterSets()
        val first = join(vps, sps, pps, nal(19, ByteArray(16) { 0x44 }))
        val second = nal(1, ByteArray(8) { 0x55 })
        val bytes = header() + partition(first) + block(0) + partition(second)

        val container = WxgfFormat.parse(bytes)!!

        assertEquals(WxgfFormat.DEFAULT_FRAME_DELAY_MS, 50)
        assertArrayEquals(intArrayOf(50, 50), container.frameDelaysMs)
    }

    @Test
    fun headerCarriesUnifiedDelayFor19ByteContainers() {
        val (vps, sps, pps) = parameterSets()
        val first = join(vps, sps, pps, nal(19, ByteArray(16) { 0x44 }))
        val second = join(nal(1, ByteArray(8) { 0x55 }), nal(1, ByteArray(8) { 0x66 }))
        // 头长度 19 的容器把统一时长写在偏移 17，单位 2ms：50 → 100ms
        val bytes = header(headerLength = 19, delayUnit = 50) +
            partition(first) +
            block(0) + block(0) +
            partition(second)

        val container = WxgfFormat.parse(bytes)!!

        assertArrayEquals(intArrayOf(100, 100, 100), container.frameDelaysMs)
    }

    @Test
    fun headerDelayUnitIsTwoMilliseconds() {
        val (vps, sps, pps) = parameterSets()
        val first = join(vps, sps, pps, nal(19, ByteArray(16) { 0x44 }))
        // 实测 25 → 50ms、50 → 100ms；这里取 40 验证确实是「乘 2」而不是「等于 50 的巧合」
        val bytes = header(headerLength = 19, delayUnit = 40) + partition(first)

        assertArrayEquals(intArrayOf(80), WxgfFormat.parse(bytes)!!.frameDelaysMs)
    }

    @Test
    fun headerDelayAlsoAppliesTo20ByteContainers() {
        val (vps, sps, pps) = parameterSets()
        val first = join(vps, sps, pps, nal(19, ByteArray(16) { 0x44 }))
        // 头长度 20 只是尾部多一个字节，时长字段位置不变：50 → 100ms
        val bytes = header(headerLength = 20, delayUnit = 50) + partition(first)

        assertArrayEquals(intArrayOf(100), WxgfFormat.parse(bytes)!!.frameDelaysMs)
    }

    @Test
    fun fpsHeaderIsConvertedToMilliseconds() {
        val (vps, sps, pps) = parameterSets()
        val first = join(vps, sps, pps, nal(19, ByteArray(16) { 0x44 }))
        // 头长度 21（版本 2）写的是帧率：10fps → 100ms
        val bytes = header(headerLength = 21, delayUnit = 10) + partition(first)

        assertArrayEquals(intArrayOf(100), WxgfFormat.parse(bytes)!!.frameDelaysMs)
    }

    @Test
    fun slowFpsHeaderMeansLongerFrames() {
        val (vps, sps, pps) = parameterSets()
        val first = join(vps, sps, pps, nal(19, ByteArray(16) { 0x44 }))
        // 5fps → 200ms
        val bytes = header(headerLength = 21, delayUnit = 5) + partition(first)

        assertArrayEquals(intArrayOf(200), WxgfFormat.parse(bytes)!!.frameDelaysMs)
    }

    @Test
    fun blockDelayWinsOverHeaderDefault() {
        val (vps, sps, pps) = parameterSets()
        val first = join(vps, sps, pps, nal(19, ByteArray(16) { 0x44 }))
        val second = nal(1, ByteArray(8) { 0x55 })
        // 逐帧时长比头里的统一值更具体，写了就该用它
        val bytes = header(headerLength = 19, delayUnit = 50) +
            partition(first) +
            block(70) +
            partition(second)

        assertArrayEquals(intArrayOf(100, 70), WxgfFormat.parse(bytes)!!.frameDelaysMs)
    }

    @Test
    fun ignoresHeaderDelayForOlderContainers() {
        val (vps, sps, pps) = parameterSets()
        val first = join(vps, sps, pps, nal(19, ByteArray(16) { 0x44 }))
        // 头长度 18 没有这一项，偏移 17 是别的字段，不能拿来当时长
        val bytes = header(headerLength = 18) + partition(first)

        assertArrayEquals(intArrayOf(50), WxgfFormat.parse(bytes)!!.frameDelaysMs)
    }

    @Test
    fun fallsBackToDefaultWhenBlockIsNotRecognised() {
        val (vps, sps, pps) = parameterSets()
        val first = join(vps, sps, pps, nal(19, ByteArray(16) { 0x44 }))
        val second = nal(1, ByteArray(8) { 0x55 })
        // 私有块长度不足以构成一帧的元数据：不该硬猜，按默认速度走
        val bytes = header() + partition(first) + ByteArray(6) + partition(second)

        val container = WxgfFormat.parse(bytes)!!

        assertArrayEquals(intArrayOf(50, 50), container.frameDelaysMs)
    }

    @Test
    fun skipsFalseStartCodeBetweenPartitions() {
        val (vps, sps, pps) = parameterSets()
        val first = join(vps, sps, pps, nal(19, ByteArray(64) { 0x44 }))
        val second = nal(1, ByteArray(32) { 0x55 })
        // 分区之间夹着的私有块里含有假起始码，长度字段是越界的垃圾值：
        // 必须跳过它继续往后找，否则表情数据会被切碎
        val junk = byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()) +
            byteArrayOf(0, 0, 1, 0, 0, 0x0F) +
            ByteArray(16) { 0x66 }
        val bytes = header() + partition(first) + junk + partition(second)

        val container = WxgfFormat.parse(bytes)!!

        assertEquals(2, container.frames.size)
        assertArrayEquals(second, container.frames[1])
    }

    @Test
    fun acceptsThreeByteStartCodes() {
        val body = join(
            byteArrayOf(0, 0, 1, 0x40, 0x01) + ByteArray(10) { 0x11 },
            byteArrayOf(0, 0, 1, 0x42, 0x01) + ByteArray(10) { 0x22 },
            byteArrayOf(0, 0, 1, 0x44, 0x01) + ByteArray(4) { 0x33 },
            byteArrayOf(0, 0, 1, 0x26, 0x01) + ByteArray(8) { 0x44 }
        )
        val bytes = header() + u32(body.size) + body

        val container = WxgfFormat.parse(bytes)!!

        assertEquals(1, container.frames.size)
        assertArrayEquals(body, container.frames[0])
    }

    @Test
    fun rejectsContainerWithoutParameterSets() {
        // 只有图像帧、没有 VPS/SPS/PPS：解不出来，交给上层判为不可解
        val bytes = header() + partition(nal(19, ByteArray(64) { 0x44 }))

        assertNull(WxgfFormat.parse(bytes))
    }

    @Test
    fun rejectsContainersWithoutPartitions() {
        assertNull(WxgfFormat.parse(header()))
    }

    @Test
    fun rejectsBrokenHeader() {
        val (vps, sps, pps) = parameterSets()
        val body = partition(join(vps, sps, pps, nal(19, ByteArray(16))))

        assertNull(WxgfFormat.parse("GIF89a".toByteArray() + ByteArray(64)))
        assertNull(WxgfFormat.parse(header(width = 0) + body))
        assertNull(WxgfFormat.parse(header(height = 9000) + body))
        // 头长度字段超过上限，说明根本不是 wxgf
        assertNull(WxgfFormat.parse(header(headerLength = 70) + body))
        assertNull(WxgfFormat.parse(ByteArray(8)))
    }

    @Test
    fun recognisesMagicOnly() {
        assertTrue(WxgfFormat.isWxgf("wxgf".toByteArray()))
        assertFalse(WxgfFormat.isWxgf("wxg".toByteArray()))
        assertFalse(WxgfFormat.isWxgf("GIF89a".toByteArray()))
    }

    @Test
    fun annexBJoinsFrames() {
        val (vps, sps, pps) = parameterSets()
        val first = join(vps, sps, pps, nal(19, ByteArray(8) { 1 }))
        val second = nal(1, ByteArray(8) { 2 })
        val container = WxgfFormat.parse(
            header() + partition(first) + partition(second)
        )!!

        val joined = container.annexB()

        assertEquals(first.size + second.size, joined.size)
        assertArrayEquals(join(first, second), joined)
    }

    @Test
    fun lengthPrefixedKeepsParameterSetsForMuxer() {
        val (vps, sps, pps) = parameterSets()
        val slice = nal(19, ByteArray(6) { 0x44 })
        // MediaMuxer 收 csd 时按 hvcC 解析，参数集要带上、起始码换成 4 字节长度
        val expected = listOf(vps, sps, pps, slice).fold(ByteArray(0)) { acc, unit ->
            val body = unit.copyOfRange(startCode.size, unit.size)
            acc + byteArrayOf(0, 0, 0, body.size.toByte()) + body
        }

        assertArrayEquals(expected, WxgfFormat.lengthPrefixed(join(vps, sps, pps, slice)))
    }

    @Test
    fun sampleDataUsesLengthPrefixAndDropsParameterSets() {
        val (vps, sps, pps) = parameterSets()
        val slice = nal(19, ByteArray(6) { 0x44 })
        val frame = join(vps, sps, pps, slice)

        val sample = WxgfFormat.toSampleData(frame)

        // 只剩图像 NAL：4 字节长度 + 载荷（起始码被换掉）
        val payload = slice.copyOfRange(startCode.size, slice.size)
        val expected = byteArrayOf(0, 0, 0, payload.size.toByte()) + payload
        assertArrayEquals(expected, sample)
    }

    @Test
    fun sampleDataOfParameterSetOnlyIsEmpty() {
        val (vps, sps, pps) = parameterSets()

        assertTrue(WxgfFormat.toSampleData(join(vps, sps, pps)).isEmpty())
    }

    private companion object {
        /** 与实现里的 MIN_HEADER_BYTES 对齐：魔数 4 + 长度 1 + 版本 2 + 宽高 4。 */
        const val MIN_HEADER = 11

        /** 头长度 19/20 的容器在偏移 17 写统一帧时长（单位 2ms）。 */
        const val DEFAULT_DELAY_OFFSET = 17

        /** 头长度 21 起把帧率写在偏移 18。 */
        const val FPS_OFFSET = 18
    }
}
