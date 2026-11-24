package top.aico.wxcacheviewer.data.emoji

import android.graphics.Bitmap
import top.aico.wxcacheviewer.data.log.AppLog
import top.aico.wxcacheviewer.domain.emoji.EmojiCrypto
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
 * 转换失败返回 null，调用方当作「这份表情打不开」处理，卡片显示占位而不是乱码。
 */
object EmojiContent {

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
        val plain = if (encrypted) {
            val resolved = key ?: return null
            EmojiCrypto.decryptContent(source, resolved) ?: return null
        } else {
            source
        }
        if (!WxgfFormat.isWxgf(plain)) return Content(plain)
        return decodeFrame(plain, workDir)
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
