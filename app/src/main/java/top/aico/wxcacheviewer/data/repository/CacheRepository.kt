package top.aico.wxcacheviewer.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.aico.wxcacheviewer.data.root.RootCommandExecutor
import top.aico.wxcacheviewer.data.root.RootState
import top.aico.wxcacheviewer.data.root.WxPaths
import top.aico.wxcacheviewer.domain.model.Account

class CacheRepository(private val executor: RootCommandExecutor) {

    suspend fun checkRoot(): RootState = executor.checkRoot()

    suspend fun discoverAccounts(): List<Account> = withContext(Dispatchers.IO) {
        val listing = executor.run(WxPaths.discoverAccountsCommand())
        if (listing.exitCode != 0) {
            throw IllegalStateException(listing.stderr.ifBlank { "账号目录读取失败" })
        }
        val hashes = listing.stdout.lines()
            .map { it.trim() }
            .filter(WxPaths::isValidAccountHash)
            .distinct()
        if (hashes.isEmpty()) return@withContext emptyList()

        val withCache = executor.run(WxPaths.checkVideoDirsCommand(hashes))
        if (withCache.exitCode != 0) {
            throw IllegalStateException(withCache.stderr.ifBlank { "缓存目录检测失败" })
        }
        val cached = withCache.stdout.lines()
            .map { it.trim() }
            .filter(WxPaths::isValidAccountHash)
            .toSet()

        hashes.map { Account(it, it in cached) }
            .sortedWith(compareByDescending<Account> { it.hasVideoCache }.thenBy { it.hash })
    }
}
