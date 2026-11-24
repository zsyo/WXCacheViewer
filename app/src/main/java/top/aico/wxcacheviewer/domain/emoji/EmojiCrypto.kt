package top.aico.wxcacheviewer.domain.emoji

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 微信表情缓存（`MicroMsg/<hash>/emoji/<32 位 md5>`）的解密。
 *
 * 实测结论（Android 微信 8.x，真机校验过 GIF / PNG 两种样本）：
 * 1. 只有**前 1024 字节**被 AES-128-ECB 加密，其余部分是明文，因此长度不变；
 * 2. 密钥由 uin 推导：`md5(十进制 uin)` 的十六进制前 16 个字符，直接当作 16 字节 ASCII 密钥。
 *    这一点与网上流传的「md5(imei)」不同，实测 md5(uin) 才能解出来；
 * 3. 解密后整份内容的 md5 正好等于文件名，可用来校验密钥是否正确。
 *
 * 加密只覆盖头部，所以预览/导出都只需要改写前 1024 字节，不必整文件重写。
 */
// 微信落盘就是 AES-128-ECB，没有 IV、也不分块链接；换成别的模式就解不开，lint 的 ECB 告警在这里不适用。
@Suppress("GetInstance")
object EmojiCrypto {

    /** 被加密的头部长度。 */
    const val HEAD_BYTES = 1024

    /** AES 分组长度，明密文长度都必须是它的整数倍。 */
    private const val BLOCK_BYTES = 16

    /** 密钥取自 md5 十六进制串的长度。 */
    private const val KEY_CHARS = 16

    private const val KEY_ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/ECB/NoPadding"

    /** 微信私有的 wxgf 动图头，解出来也无法用系统解码器打开。 */
    private val WXGF_MAGIC = byteArrayOf(0x77, 0x78, 0x67, 0x66)

    /**
     * 由 uin 推导表情密钥。uin 必须是纯数字；拿不到合法 uin 时返回 null，
     * 调用方据此退回「只展示明文表情」。
     */
    fun keyOf(uin: String): ByteArray? {
        val value = uin.trim()
        if (value.isEmpty() || value.length > MAX_UIN_LENGTH) return null
        if (!value.all { it in '0'..'9' }) return null
        val digest = MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString(separator = "") { "%02x".format(it.toInt() and 0xFF) }
        return hex.take(KEY_CHARS).toByteArray(Charsets.US_ASCII)
    }

    /**
     * 解密一段密文。长度会向下取整到分组边界（不足一个分组的尾巴本来就取不到明文），
     * 密钥或密文有问题时返回 null，而不是抛异常。
     */
    fun decrypt(cipherBytes: ByteArray, key: ByteArray): ByteArray? {
        val length = cipherBytes.size / BLOCK_BYTES * BLOCK_BYTES
        if (length <= 0 || key.size != KEY_CHARS) return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, KEY_ALGORITHM))
            cipher.doFinal(cipherBytes, 0, length)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 解密列表命令拿到的文件头（十六进制）。
     *
     * 列目录时 shell 已经读了前 32 字节，ECB 按分组独立解密，
     * 所以这 32 字节足够判断真实类型，不必额外再读一次文件。
     */
    fun decryptHeaderHex(headerHex: String, key: ByteArray): ByteArray? {
        val cleaned = headerHex.filter { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        val byteCount = cleaned.length / 2
        if (byteCount < BLOCK_BYTES) return null
        val bytes = ByteArray(byteCount) { index ->
            val high = Character.digit(cleaned[index * 2], 16)
            val low = Character.digit(cleaned[index * 2 + 1], 16)
            ((high shl 4) or low).toByte()
        }
        return decrypt(bytes, key)
    }

    /** 解密出来的内容是不是微信私有 wxgf（HEVC 封装，系统解码器打不开）。 */
    fun isWxgf(bytes: ByteArray): Boolean =
        bytes.size >= WXGF_MAGIC.size &&
            WXGF_MAGIC.indices.all { bytes[it] == WXGF_MAGIC[it] }

    private const val MAX_UIN_LENGTH = 20
}

/**
 * 按账号目录给出表情密钥。拿不到（多账号、prefs 不可读、uin 与目录不匹配）时返回 null，
 * 上层据此跳过加密表情，而不是展示一堆乱码。
 */
fun interface EmojiKeyProvider {

    suspend fun keyFor(accountHash: String): ByteArray?

    companion object {
        /** 不解密的兜底实现，用于测试与未接线的调用点。 */
        val None: EmojiKeyProvider = EmojiKeyProvider { null }
    }
}
