package top.aico.wxcacheviewer.data.emoji

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.aico.wxcacheviewer.data.log.AppLog
import top.aico.wxcacheviewer.data.root.RootCommandExecutor
import top.aico.wxcacheviewer.data.root.WxPaths
import top.aico.wxcacheviewer.domain.UinHash
import top.aico.wxcacheviewer.domain.emoji.EmojiCrypto
import top.aico.wxcacheviewer.domain.emoji.EmojiKeyProvider

/**
 * 表情解密密钥的来源。
 *
 * 微信把「md5(imei)」这条密钥写在自己的加密数据库里，但实测 Android 8.x 的表情文件
 * 直接用 `md5(uin)` 前 16 位就能解开，而 uin 在 `shared_prefs` 里，root 可读。
 *
 * 只认「最后登录账号」的 uin，并用 `md5("mm" + uin) == 账号目录名` 校验归属：
 * 校验不过（切了账号、多账号并存）时返回 null，让上层退回只展示明文表情，
 * 而不是拿别人的密钥去解一堆乱码。
 */
class EmojiKeyStore(private val executor: RootCommandExecutor) : EmojiKeyProvider {

    private val lock = Mutex()
    private var uin: String? = null
    private var resolved = false

    override suspend fun keyFor(accountHash: String): ByteArray? {
        val value = loginUin() ?: return null
        if (UinHash.of(value) != accountHash) return null
        return EmojiCrypto.keyOf(value)
    }

    /** 读一次就记住；失败也记，避免每页都去 fork 一次 grep。 */
    private suspend fun loginUin(): String? = lock.withLock {
        if (resolved) return@withLock uin
        val result = try {
            executor.run(WxPaths.readLoginUinCommand(), TIMEOUT_MS)
        } catch (e: Exception) {
            AppLog.log("emoji", "login uin failed: ${e.message}")
            null
        }
        uin = result
            ?.takeIf { it.exitCode == 0 }
            ?.let { UIN_REGEX.find(it.stdout)?.groupValues?.getOrNull(1) }
        resolved = true
        AppLog.log("emoji", "login uin resolved=${uin != null}")
        uin
    }

    private companion object {
        const val TIMEOUT_MS = 8_000L
        val UIN_REGEX = Regex("name=\"last_login_uin\">(\\d{1,20})")
    }
}
