package top.aico.wxcacheviewer.domain

/**
 * 微信语音文件的格式识别与私有头处理。
 *
 * `voice2` 目录下的文件后缀是 `.amr`，内容却是 SILK v3：标准 SILK 以 `#!SILK_V3`
 * 开头、以 `0xFF 0xFF` 结束，微信在标准头前面插了一个 `0x02` 私有字节，并去掉了结束标记。
 * 标准解码器读到首字节是 `0x02` 会直接帧同步失败，所以解码前必须把这个字节剥掉。
 */
object VoiceFormat {

    private val SILK_HEADER = "#!SILK_V3".toByteArray(Charsets.US_ASCII)
    private val AMR_HEADER = "#!AMR".toByteArray(Charsets.US_ASCII)

    /** 微信私有魔数，紧贴在标准 SILK 头之前。 */
    private const val WEIXIN_SILK_MAGIC: Byte = 0x02

    /** SILK 帧长固定 20ms。 */
    private const val FRAME_DURATION_MS = 20L

    enum class Kind {
        /** 标准 SILK v3，可直接交给解码器。 */
        SILK,

        /** 微信私有封装：`0x02` + `#!SILK_V3`。 */
        WEIXIN_SILK,

        /** 真正的 AMR，播放器可直接解码。 */
        AMR,

        /** 无法识别，不尝试播放。 */
        UNKNOWN
    }

    fun kindOf(bytes: ByteArray): Kind = when {
        startsWith(bytes, 0, SILK_HEADER) -> Kind.SILK
        bytes.isNotEmpty() && bytes[0] == WEIXIN_SILK_MAGIC &&
            startsWith(bytes, 1, SILK_HEADER) -> Kind.WEIXIN_SILK
        startsWith(bytes, 0, AMR_HEADER) -> Kind.AMR
        else -> Kind.UNKNOWN
    }

    /** 去掉微信私有头，返回标准 SILK 流；本身已是标准格式时原样返回。 */
    fun toStandardSilk(bytes: ByteArray): ByteArray = when (kindOf(bytes)) {
        Kind.WEIXIN_SILK -> bytes.copyOfRange(1, bytes.size)
        Kind.SILK -> bytes
        else -> throw IllegalArgumentException("Not a silk stream")
    }

    /**
     * 按帧数估算时长：SILK 每帧固定 20ms，帧头是 2 字节小端长度。
     *
     * 用来校验解码结果——老版本微信录的是 16kHz，若解码器按 24kHz 输出且没有重采样，
     * 时长会明显对不上，据此可以自动换采样率重解一次。
     */
    fun durationMillisOfStandardSilk(bytes: ByteArray): Long {
        if (kindOf(bytes) != Kind.SILK) return 0L
        var index = SILK_HEADER.size
        var frames = 0L
        while (index + 2 <= bytes.size) {
            val length = (bytes[index].toInt() and 0xFF) or
                ((bytes[index + 1].toInt() and 0xFF) shl 8)
            // 标准格式以 0xFF 0xFF 结尾；越界也说明帧序列到头了
            if (length == 0xFFFF || index + 2 + length > bytes.size) break
            frames++
            index += 2 + length
        }
        return frames * FRAME_DURATION_MS
    }

    private fun startsWith(bytes: ByteArray, offset: Int, prefix: ByteArray): Boolean {
        if (bytes.size - offset < prefix.size) return false
        for (index in prefix.indices) {
            if (bytes[offset + index] != prefix[index]) return false
        }
        return true
    }
}
