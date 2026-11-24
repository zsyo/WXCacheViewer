package top.aico.wxcacheviewer.data.emoji

import android.graphics.Bitmap
import top.aico.wxcacheviewer.data.log.AppLog
import top.aico.wxcacheviewer.domain.emoji.EmojiCrypto
import top.aico.wxcacheviewer.domain.emoji.GifEncoder
import top.aico.wxcacheviewer.domain.emoji.WxgfFormat
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 把表情文件的原始字节变成「可以直接写盘、可以直接给解码器」的内容。
 *
 * 三种情况：
 * - 普通表情：原样返回，扩展名由调用方按文件类型补；
 * - 加密表情：解开前 1KB 后原样返回；
 * - wxgf：解开后仍是微信私有容器，系统不认识，这里解出首帧并转成 PNG。
 *
 * wxgf 因为本体是动图，另有一个出口：网格缩略图用 [materialize]（首帧 PNG），
 * 预览页循环播放与导出用 [materializeAnimated]（重编码 GIF）。
 *
 * 转换失败返回 null / false，调用方当作「这份表情打不开」处理，卡片显示占位而不是乱码。
 */
object EmojiContent {

    /** wxgf 导出动图的帧间隔。容器头里没有可信的帧率字段，实测 30fps 明显偏快，按 10fps 走。 */
    private const val FRAME_DELAY_MS = 100

    /**
     * 编码参数或产物格式变了就改这个串：缓存文件名里带着它，旧产物自然失效，
     * 不用等用户清缓存，也不会把按旧参数编好的动图当成新的。
     */
    const val CACHE_TAG = "gif-100ms"

    /**
     * [extension] 为 null 表示内容与源类型一致；非 null 表示已经转换过，
     * 落盘与导出都要用这个扩展名。
     */
    class Content(val bytes: ByteArray, val extension: String? = null)

    fun materialize(
        source: ByteArray,
        key: ByteArray?,
        encrypted: Boolean,
        workDir: File
    ): Content? {
        val plain = plain(source, key, encrypted) ?: return null
        if (!WxgfFormat.isWxgf(plain)) return Content(plain)
        return decodeFrame(plain, workDir)
    }

    /**
     * wxgf 的动图版本：逐帧解码后重新编码成 GIF，预览页循环播放与导出都用它。
     *
     * 为什么是 GIF 而不是封装 MP4：这条路上所有环节都是自己的代码（解码器 + [GifEncoder]），
     * 单测能把编出来的字节解回像素逐帧比对；交给 `MediaMuxer` 反而踩过坑——csd 的两种
     * 约定（解码器要 Annex-B、封装器要 hvcC 长度前缀）混用会写出播不了的 MP4。
     * 而且表情页里的 GIF 表情本来就走 Coil 动画，格式统一后预览页只有一条路径。
     *
     * 不是 wxgf 时返回 false——那类表情本来就是能直接打开的 GIF/PNG/JPEG。
     */
    fun materializeAnimated(
        source: ByteArray,
        key: ByteArray?,
        encrypted: Boolean,
        target: File
    ): Boolean {
        val plain = plain(source, key, encrypted) ?: return false
        if (!WxgfFormat.isWxgf(plain)) return false
        val container = WxgfFormat.parse(plain)
        if (container == null) {
            AppLog.log("emoji", "wxgf header parse failed size=${plain.size}")
            return false
        }
        val frames = WxgfDecoder.decodeFrames(container)
        if (frames.isEmpty()) {
            AppLog.log("emoji", "wxgf gif no frames ${container.width}x${container.height}")
            return false
        }
        val first = frames.first()
        target.outputStream().use { out ->
            GifEncoder.encode(first.width, first.height, frames.map { it.argb }, FRAME_DELAY_MS, out)
        }
        AppLog.log("emoji", "wxgf gif frames=${frames.size} bytes=${target.length()}")
        return target.length() > 0
    }

    private fun plain(source: ByteArray, key: ByteArray?, encrypted: Boolean): ByteArray? {
        if (!encrypted) return source
        val resolved = key ?: return null
        return EmojiCrypto.decryptContent(source, resolved)
    }

    private fun decodeFrame(plain: ByteArray, workDir: File): Content? {
        val container = WxgfFormat.parse(plain)
        if (container == null) {
            AppLog.log("emoji", "wxgf header parse failed size=${plain.size}")
            return null
        }
        val bitmap = WxgfDecoder.decodeFirstFrame(container, workDir)
        if (bitmap == null) {
            AppLog.log(
                "emoji",
                "wxgf decode failed ${container.width}x${container.height} " +
                    "frames=${container.frames.size}"
            )
            return null
        }
        val out = ByteArrayOutputStream()
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)) {
            AppLog.log("emoji", "wxgf png encode failed")
            bitmap.recycle()
            return null
        }
        bitmap.recycle()
        return Content(out.toByteArray(), PNG_EXTENSION)
    }

    /** wxgf 解出来的是动图首帧，落盘统一用 PNG。 */
    const val PNG_EXTENSION = "png"

    private const val PNG_QUALITY = 100
}
