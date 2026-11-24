package top.aico.wxcacheviewer.domain.emoji

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class EmojiCryptoTest {

    /** 示例 uin；项目内不使用真实账号值。 */
    private val uin = "1000000001"

    private fun encrypt(plain: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(plain)
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString(separator = "") { "%02x".format(it.toInt() and 0xFF) }

    @Test
    fun keyIsAsciiPrefixOfUinMd5() {
        val key = EmojiCrypto.keyOf(uin)
        assertEquals("40433343a063d260", String(key!!, Charsets.US_ASCII))
        assertEquals(16, key.size)
    }

    @Test
    fun keyRejectsNonNumericUin() {
        assertNull(EmojiCrypto.keyOf(""))
        assertNull(EmojiCrypto.keyOf("wxid_abc"))
        assertNull(EmojiCrypto.keyOf("123456789012345678901"))
    }

    @Test
    fun decryptsHeaderGroupByGroup() {
        val key = EmojiCrypto.keyOf(uin)!!
        val plain = ByteArray(1024) { (it * 7).toByte() }
        val cipher = encrypt(plain, key)

        assertArrayEquals(plain, EmojiCrypto.decrypt(cipher, key))
    }

    @Test
    fun decryptsHeaderHexForTypeDetection() {
        val key = EmojiCrypto.keyOf(uin)!!
        val plain = "GIF89a".toByteArray() + ByteArray(26) { it.toByte() }
        val headerHex = hex(encrypt(plain, key))

        val decrypted = EmojiCrypto.decryptHeaderHex(headerHex, key)
        assertArrayEquals(plain, decrypted)
        assertFalse(EmojiCrypto.isWxgf(decrypted!!))
    }

    @Test
    fun headerShorterThanOneBlockIsRejected() {
        val key = EmojiCrypto.keyOf(uin)!!
        assertNull(EmojiCrypto.decryptHeaderHex("00".repeat(15), key))
    }

    @Test
    fun recognizesWxgfMagic() {
        val plain = "wxgf".toByteArray() + ByteArray(28)
        assertTrue(EmojiCrypto.isWxgf(plain))
        assertFalse(EmojiCrypto.isWxgf("GIF89a".toByteArray()))
    }
}
