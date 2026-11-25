package top.aico.wxcacheviewer.domain.emoji

/**
 * 微信私有的 wxgf 容器（表情目录里解开头部后以 `wxgf` 开头的文件）。
 *
 * 结构（真机取样 + 社区文档互相印证）：
 * ```
 * 0..3    "wxgf"
 * 4       主头长度（字节，实测量到 18）
 * 5..6    版本（2 字节大端，实测 0）
 * 7..8    宽（2 字节大端）
 * 9..10   高（2 字节大端）
 * 11..    其余头字段（位打包的帧率、质量等，未用到）
 * ```
 * 主头之后是若干个「分区」：分区前 4 字节是大端长度，紧跟 `00 00 00 01` 起始码和
 * 标准 H.265 Annex-B 码流；分区之间还夹着约 100 字节的私有块。把分区首尾相接、
 * 去掉那些私有块，得到的就是一段干净的 HEVC 码流（首段里带 VPS/SPS/PPS）。
 *
 * 所以解码不需要 ffmpeg：抠出参数集交给 MediaCodec，就能出图。
 *
 * 私有块里还藏着**逐帧播放时长**：每个块头 2 字节是该帧的毫秒数。写 0 表示「没指定」，
 * 这时用容器头里的统一时长（头长度 19 才有这一项，单位 2ms）。都拿不到才落到 50ms。
 * 这些数字都与微信自己把 wxgf 转出来的 GIF 逐帧比对过，见 [frameDelaysMs] 与 [defaultFrameDelay]。
 */
object WxgfFormat {

    private val MAGIC = byteArrayOf(0x77, 0x78, 0x67, 0x66)

    /** 主头长度字段所在偏移。 */
    private const val HEADER_LEN_OFFSET = 4

    /** 宽高字段所在偏移，2 字节大端。 */
    private const val WIDTH_OFFSET = 7
    private const val HEIGHT_OFFSET = 9

    private val START_CODE_4 = byteArrayOf(0, 0, 0, 1)
    private val START_CODE_3 = byteArrayOf(0, 0, 1)

    private const val NAL_VPS = 32
    private const val NAL_SPS = 33
    private const val NAL_PPS = 34
    private const val NAL_AUD = 35
    private const val NAL_PREFIX_SEI = 39

    /** 参数集类型：写 MP4 样本时跳过，交给 csd。 */
    private val PARAMETER_SET_TYPES = setOf(NAL_VPS, NAL_SPS, NAL_PPS)

    /** 这些非图像 NAL 跟在画面后面出现时，说明下一帧要开始了。 */
    private val PREFIX_NAL_TYPES = setOf(NAL_VPS, NAL_SPS, NAL_PPS, NAL_AUD, NAL_PREFIX_SEI)

    /** 主头至少要有魔数、长度、版本、宽高这些字段。 */
    private const val MIN_HEADER_BYTES = 11

    /** 头长度字段的合理上限，超过就认为不是 wxgf。 */
    private const val MAX_HEADER_BYTES = 64

    /** 表情边长实测 320；给一个宽松但能挡住误判的区间。 */
    private const val MIN_SIDE = 16
    private const val MAX_SIDE = 8192

    /**
     * 拿不到任何时长信息时的兜底值。
     *
     * 注意它**不是**通用默认值：头长度 19 的容器会在头部另写一个默认时长，
     * 私有块里 0 表示「用头部那个」（见 [defaultFrameDelay]）。只有头里也没有时，
     * 才退到这里的 50ms——早期样本（41 帧、100 帧）转出来的 GIF 正好都是 5 厘秒一帧。
     */
    const val DEFAULT_FRAME_DELAY_MS = 50

    /** 头长度 19/20 的容器在偏移 17 处多一个「默认帧时长」字段。 */
    private const val HEADER_WITH_DEFAULT_DELAY = 19

    /** 头长度 21 起（版本字段为 2）换了个布局：字段后移到偏移 18，且改成写帧率。 */
    private const val HEADER_WITH_FPS = 21
    private const val FPS_OFFSET = 18
    private const val DEFAULT_DELAY_OFFSET = 17

    /** 19/20 字节的表里单位是 2ms：25 → 50ms，50 → 100ms。 */
    private const val DEFAULT_DELAY_UNIT_MS = 2

    /** 合理帧时长区间：低于 10ms 的 GIF 计时会退化成 1 厘秒，超过 1s 基本是认错了字段。 */
    private const val MIN_FRAME_DELAY_MS = 10
    private const val MAX_FRAME_DELAY_MS = 1000

    /** 私有块最短长度：2 字节时长 + 长度字段 + 若干头字节。 */
    private const val MIN_BLOCK_BYTES = 16

    /** 一个间隙里最多这么多个私有块，再多就不像「一帧一块」了。 */
    private const val MAX_BLOCKS_PER_GAP = 4

    /**
     * 一段可解码的 wxgf 内容。
     *
     * [frames] 每个元素是一段独立的 Annex-B 码流（对应容器里的一个分区），
     * 首段带 VPS/SPS/PPS；[parameterSets] 是从里抠出来的 csd；
     * [frameDelaysMs] 与 [units] 一一对应，是该帧的播放时长（毫秒）。
     */
    class Container private constructor(
        val width: Int,
        val height: Int,
        val frames: List<ByteArray>,
        val parameterSets: ByteArray,
        val frameDelaysMs: IntArray
    ) {
        /**
         * 按访问单元（AU）切好的码流，一个元素就是一帧画面。
         *
         * 必须按 AU 喂解码器：容器里的一个分区常常装了不止一帧（实测 32 个分区装着
         * 63 帧），整段当成「一个样本」交出去属于非法数据，解码器直接丢弃，
         * 真机上的表现就是首帧怎么都取不到。
         */
        val units: List<ByteArray> = splitAccessUnits(annexB())

        /** 分区首尾相接的完整码流，调试与测试用。 */
        fun annexB(): ByteArray {
            val total = frames.sumOf { it.size }
            val out = ByteArray(total)
            var offset = 0
            frames.forEach { frame ->
                frame.copyInto(out, offset)
                offset += frame.size
            }
            return out
        }

        companion object {
            internal fun of(
                width: Int,
                height: Int,
                frames: List<ByteArray>,
                parameterSets: ByteArray,
                frameDelaysMs: IntArray
            ) = Container(width, height, frames, parameterSets, frameDelaysMs)
        }
    }

    fun isWxgf(bytes: ByteArray): Boolean =
        bytes.size >= MAGIC.size && MAGIC.indices.all { bytes[it] == MAGIC[it] }

    /**
     * 把一段 Annex-B 码流转成 hvcC 风格：每个 NAL 前面换成 4 字节大端长度。
     *
     * 注意两种 csd 约定不一样：`MediaCodec` 解码器要 Annex-B（带起始码），
     * 而 `MediaMuxer` 收 csd 时按 hvcC 解析、要长度前缀。混用会写出解不了的 MP4。
     *
     * 参数集（VPS/SPS/PPS）默认保留；[skipParameterSets] 为真时丢掉——
     * 写 MP4 样本时它们已经在 csd 里，重复放进样本与 hvc1 的语义不符。
     * 空 NAL 一律丢掉，否则会写出 0 长度的伪样本。
     */
    fun lengthPrefixed(annexB: ByteArray, skipParameterSets: Boolean = false): ByteArray {
        val out = java.io.ByteArrayOutputStream(annexB.size)
        for (nal in nalUnits(annexB)) {
            val size = nal.to - nal.payload
            if (size <= 1) continue
            if (skipParameterSets && nalType(annexB, nal.payload) in PARAMETER_SET_TYPES) continue
            out.write((size ushr 24) and 0xFF)
            out.write((size ushr 16) and 0xFF)
            out.write((size ushr 8) and 0xFF)
            out.write(size and 0xFF)
            out.write(annexB, nal.payload, size)
        }
        return out.toByteArray()
    }

    /**
     * 把一个访问单元转成 MP4 样本：每个 NAL 前面换成 4 字节大端长度。
     *
     * 参数集（VPS/SPS/PPS）跳过——它们已经作为 csd 交给封装器，重复放在样本里
     * 与 hvc1 的语义不符。
     */
    fun toSampleData(unit: ByteArray): ByteArray = lengthPrefixed(unit, skipParameterSets = true)

    /** 解析失败（不是 wxgf、头字段不合理、找不到分区、取不到参数集）时返回 null。 */
    fun parse(bytes: ByteArray): Container? {
        if (!isWxgf(bytes) || bytes.size <= MIN_HEADER_BYTES) return null
        val headerLength = bytes[HEADER_LEN_OFFSET].toInt() and 0xFF
        if (headerLength < MIN_HEADER_BYTES || headerLength > MAX_HEADER_BYTES) return null
        if (headerLength >= bytes.size) return null

        val width = readU16(bytes, WIDTH_OFFSET)
        val height = readU16(bytes, HEIGHT_OFFSET)
        if (width !in MIN_SIDE..MAX_SIDE || height !in MIN_SIDE..MAX_SIDE) return null

        // 绝大多数文件用 4 字节起始码；个别只有 3 字节，退一步再试。
        val partitions = scanPartitions(bytes, headerLength, START_CODE_4)
            .ifEmpty { scanPartitions(bytes, headerLength, START_CODE_3) }
            .ifEmpty { return null }

        val frames = partitions.map { bytes.copyOfRange(it.start, it.start + it.length) }
        val parameterSets = collectParameterSets(frames) ?: return null
        val perPartition = partitions.map { splitAccessUnits(it.slice(bytes)).size }
        val defaultDelay = defaultFrameDelay(bytes, headerLength)
        return Container.of(
            width,
            height,
            frames,
            parameterSets,
            frameDelays(bytes, partitions, perPartition, defaultDelay)
        )
    }

    /**
     * 容器头里的统一帧时长（毫秒）。
     *
     * 头部布局随长度变化，但偏移 11 起的结构是稳定的：`帧数-1`、两个 0、`81 80`，
     * 接着才是时长字段。区别只在版本演进：
     * - 头长度 19/20：偏移 17 是一字节时长，单位 2ms。25 → 50ms、50 → 100ms、65 → 130ms，
     *   与微信自己转出的 GIF 逐帧对上（300×300 的 41/100 帧、540×507 的 50 帧、
     *   400×400 的 16 帧、240×266 的 110 帧）。
     * - 头长度 21（版本字段 `00 02`）：多了一个字节，字段移到偏移 18，而且写的是**帧率**，
     *   10 → 100ms、5 → 200ms。这批样本没有 `_gif` 对照，是按「取值只能是 1000/n 的整数」
     *   与同批头长度 19 的 50（=10fps）推断的。
     * - 头长度 17/18：没有这一项，逐帧时长全写在私有块里。
     *
     * 值不合理时退回 [DEFAULT_FRAME_DELAY_MS]，不要被别的字段带偏。
     */
    private fun defaultFrameDelay(bytes: ByteArray, headerLength: Int): Int {
        if (headerLength >= HEADER_WITH_FPS) {
            if (FPS_OFFSET >= bytes.size) return DEFAULT_FRAME_DELAY_MS
            val fps = bytes[FPS_OFFSET].toInt() and 0xFF
            val value = if (fps > 0) 1000 / fps else 0
            return if (value in MIN_FRAME_DELAY_MS..MAX_FRAME_DELAY_MS) value else DEFAULT_FRAME_DELAY_MS
        }
        if (headerLength < HEADER_WITH_DEFAULT_DELAY) return DEFAULT_FRAME_DELAY_MS
        if (DEFAULT_DELAY_OFFSET >= bytes.size) return DEFAULT_FRAME_DELAY_MS
        val value = (bytes[DEFAULT_DELAY_OFFSET].toInt() and 0xFF) * DEFAULT_DELAY_UNIT_MS
        return if (value in MIN_FRAME_DELAY_MS..MAX_FRAME_DELAY_MS) value else DEFAULT_FRAME_DELAY_MS
    }

    /**
     * 按「4 字节大端长度 + 起始码」切出分区。
     *
     * 长度对不上（<=0 或越界）的起始码是码流里的假起始码，跳过继续找，
     * 这与 netizens 的 wxgf 解析实现一致；不这样做会把表情数据切碎。
     */
    private fun scanPartitions(bytes: ByteArray, from: Int, pattern: ByteArray): List<Segment> {
        val partitions = ArrayList<Segment>()
        val limit = bytes.size - pattern.size
        var index = from
        while (index <= limit) {
            if (matchesAt(bytes, index, pattern) && index >= 4) {
                val length = readU32(bytes, index - 4)
                if (length in 1..(bytes.size - index)) {
                    partitions += Segment(index, length)
                    index += length
                    continue
                }
            }
            index++
        }
        return partitions
    }

    /**
     * 逐帧播放时长（毫秒），与访问单元一一对应。
     *
     * 时长写在分区之间的私有块里，一个块对应紧随其后那个分区里的画面，块头 2 字节就是毫秒数。
     * 块的长度看文件，实测 100～108 字节；同一间隙里可能有多个块（一个分区装两帧），
     * 所以先按「除块头 2 字节外完全相同」找出块的个数，再按帧数等比摊开。
     *
     * 块里写 0 表示「用容器头里的统一时长」[defaultDelay]，认不出块结构时同样退到它——
     * 宁可按容器自己标的速度播，也不要被私有块里的别的字段带偏。
     */
    private fun frameDelays(
        bytes: ByteArray,
        partitions: List<Segment>,
        perPartition: List<Int>,
        defaultDelay: Int
    ): IntArray {
        val delays = IntArray(perPartition.sum()) { defaultDelay }
        var index = 0
        partitions.indices.forEach { p ->
            val count = perPartition[p]
            if (count <= 0) return@forEach
            // 分区 0 前面没有私有块，用默认值
            val blocks = if (p == 0) emptyList() else blockDelays(bytes, partitions[p - 1], partitions[p], defaultDelay)
            for (j in 0 until count) {
                if (index >= delays.size) break
                if (blocks.isNotEmpty()) {
                    // 块数少于帧数时按比例复用，多于帧数时均匀取样
                    delays[index] = blocks[(j * blocks.size) / count]
                }
                index++
            }
        }
        return delays
    }

    /** 取出 [previous] 与 [next] 之间那块私有数据里的逐帧时长。 */
    private fun blockDelays(
        bytes: ByteArray,
        previous: Segment,
        next: Segment,
        defaultDelay: Int
    ): List<Int> {
        val from = previous.start + previous.length
        // 下一分区自己还有 4 字节长度字段，不属于私有块
        val to = next.start - 4
        if (to - from < MIN_BLOCK_BYTES) return emptyList()
        val gap = bytes.copyOfRange(from, to)

        for (count in MAX_BLOCKS_PER_GAP downTo 2) {
            if (gap.size % count != 0) continue
            val size = gap.size / count
            if (size < MIN_BLOCK_BYTES) continue
            // 同一间隙里的块只有块头 2 字节（时长）不同，其余应当完全一致
            if (!sameBeyondDelay(gap, size, count)) continue
            return (0 until count).map { delayOf(gap, it * size, defaultDelay) }
        }
        return listOf(delayOf(gap, 0, defaultDelay))
    }

    private fun sameBeyondDelay(gap: ByteArray, size: Int, count: Int): Boolean {
        for (k in 1 until count) {
            for (i in 2 until size) {
                if (gap[i] != gap[k * size + i]) return false
            }
        }
        return true
    }

    /** 块头的 2 字节时长；0 或越界都按 [defaultDelay] 算。 */
    private fun delayOf(gap: ByteArray, offset: Int, defaultDelay: Int): Int {
        val value = readU16(gap, offset)
        if (value == 0) return defaultDelay
        return if (value in MIN_FRAME_DELAY_MS..MAX_FRAME_DELAY_MS) value else defaultDelay
    }

    /** 从各区段里找 VPS/SPS/PPS；缺任何一个都返回 null，交给上层判为不可解。 */
    private fun collectParameterSets(frames: List<ByteArray>): ByteArray? {
        var vps: ByteArray? = null
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        for (frame in frames) {
            for (nal in nalUnits(frame)) {
                if (nal.to - nal.payload < 2) continue
                when (nalType(frame, nal.payload)) {
                    NAL_VPS -> if (vps == null) vps = frame.copyOfRange(nal.payload, nal.to)
                    NAL_SPS -> if (sps == null) sps = frame.copyOfRange(nal.payload, nal.to)
                    NAL_PPS -> if (pps == null) pps = frame.copyOfRange(nal.payload, nal.to)
                }
            }
            if (vps != null && sps != null && pps != null) break
        }
        val vpsBody = vps ?: return null
        val spsBody = sps ?: return null
        val ppsBody = pps ?: return null

        val out = ArrayList<Byte>(
            START_CODE_4.size * 3 + vpsBody.size + spsBody.size + ppsBody.size
        )
        listOf(vpsBody, spsBody, ppsBody).forEach { body ->
            START_CODE_4.forEach { out += it }
            body.forEach { out += it }
        }
        return out.toByteArray()
    }

    /**
     * 把一段 Annex-B 码流切成访问单元（AU），每个元素是一帧画面。
     *
     * 判据取最省事的一种：图像 NAL（类型 0..31）的第 3 个字节最高位是
     * `first_slice_segment_in_pic_flag`，为 1 说明这是新一帧的首个分片；
     * 参数集 / AUD / 前缀 SEI 跟在画面后面出现时，同样当作下一帧的开头。
     * wxgf 的码流实测每帧只有一个分片，这个判据够用。
     */
    fun splitAccessUnits(bytes: ByteArray): List<ByteArray> {
        val units = ArrayList<ByteArray>()
        var from = -1
        var to = -1
        var inPicture = false
        for (nal in nalUnits(bytes)) {
            if (from < 0) from = nal.from
            val type = nalType(bytes, nal.payload)
            if (inPicture && startsAccessUnit(bytes, nal, type)) {
                units += bytes.copyOfRange(from, to)
                from = nal.from
                inPicture = false
            }
            if (type < NAL_VPS) inPicture = true
            to = nal.to
        }
        if (from >= 0 && to > from) units += bytes.copyOfRange(from, to)
        return units
    }

    private fun startsAccessUnit(bytes: ByteArray, nal: Nal, type: Int): Boolean =
        if (type < NAL_VPS) firstSliceSegment(bytes, nal) else type in PREFIX_NAL_TYPES

    /** 读 `slice_segment_header` 的第一个比特：是否为该帧的首个分片。 */
    private fun firstSliceSegment(bytes: ByteArray, nal: Nal): Boolean {
        val flagIndex = nal.payload + 2
        // 单分片以外的极端情况读不到值，保守地当新帧，宁可切碎也不要把两帧粘一起
        if (flagIndex >= nal.to) return true
        return (bytes[flagIndex].toInt() and 0x80) != 0
    }

    /** 一个 NAL 单元在源字节里的位置：[from] 是起始码起点，[payload] 是 NAL 头起点。 */
    private class Nal(val from: Int, val payload: Int, val to: Int)

    /** 一个分区在源字节里的位置：从起始码起算 [length] 字节。 */
    private class Segment(val start: Int, val length: Int) {
        fun slice(bytes: ByteArray): ByteArray = bytes.copyOfRange(start, start + length)
    }

    /** 按起始码切开码流，保留每个 NAL 的起始码位置，便于原样切片。 */
    private fun nalUnits(bytes: ByteArray): List<Nal> {
        val starts = ArrayList<Int>()
        var index = 0
        while (index <= bytes.size - START_CODE_3.size) {
            if (matchesAt(bytes, index, START_CODE_4)) {
                starts += index
                index += START_CODE_4.size
            } else if (matchesAt(bytes, index, START_CODE_3)) {
                starts += index
                index += START_CODE_3.size
            } else {
                index++
            }
        }
        return starts.mapIndexed { order, from ->
            val payload = from + if (matchesAt(bytes, from, START_CODE_4)) 4 else 3
            val to = if (order + 1 < starts.size) starts[order + 1] else bytes.size
            Nal(from, payload, maxOf(payload, to))
        }
    }

    /** HEVC 的 NAL 类型：首个字节的高 6 位。 */
    private fun nalType(frame: ByteArray, start: Int): Int =
        ((frame[start].toInt() and 0xFF) shr 1) and 0x3F

    private fun matchesAt(bytes: ByteArray, index: Int, pattern: ByteArray): Boolean {
        if (index < 0 || index + pattern.size > bytes.size) return false
        return pattern.indices.all { bytes[index + it] == pattern[it] }
    }

    private fun readU16(bytes: ByteArray, offset: Int): Int {
        if (offset + 1 >= bytes.size) return 0
        return ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
    }

    private fun readU32(bytes: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 3 >= bytes.size) return -1
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }
}
