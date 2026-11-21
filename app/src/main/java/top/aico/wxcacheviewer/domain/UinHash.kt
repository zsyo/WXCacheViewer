package top.aico.wxcacheviewer.domain

import java.security.MessageDigest

/**
 * 微信账号目录名与 uin 的换算关系：目录名 = md5("mm" + uin)。
 *
 * 只能由 uin 推导目录名，无法从目录名反推 uin。用于校验「最后登录账号」的昵称
 * 是否属于某个目录，避免张冠李戴。
 */
object UinHash {

    fun of(uin: String): String? {
        val value = uin.trim()
        if (value.isEmpty() || value.length > MAX_UIN_LENGTH) return null
        if (!value.all { it in '0'..'9' }) return null
        val digest = MessageDigest.getInstance("MD5")
            .digest((HASH_PREFIX + value).toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { "%02x".format(it.toInt() and 0xFF) }
    }

    private const val HASH_PREFIX = "mm"
    private const val MAX_UIN_LENGTH = 20
}
