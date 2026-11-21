package top.aico.wxcacheviewer.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.aico.wxcacheviewer.data.root.AccountDiscoveryParser
import top.aico.wxcacheviewer.data.root.RootCommandExecutor
import top.aico.wxcacheviewer.data.root.RootState
import top.aico.wxcacheviewer.data.root.WxPaths
import top.aico.wxcacheviewer.domain.FileTypeDetector
import top.aico.wxcacheviewer.domain.model.Account
import top.aico.wxcacheviewer.domain.model.CacheFile

class CacheRepository(private val executor: RootCommandExecutor) {

    suspend fun checkRoot(): RootState = executor.checkRoot()

    suspend fun discoverAccounts(): List<Account> = withContext(Dispatchers.IO) {
        val listing = executor.run(WxPaths.discoverAccountsCommand())
        if (listing.exitCode != 0 && listing.stdout.isBlank()) {
            throw IllegalStateException(listing.stderr.ifBlank { "账号目录读取失败" })
        }
        AccountDiscoveryParser.parse(listing.stdout)
    }

    suspend fun listFiles(hash: String): List<CacheFile> = withContext(Dispatchers.IO) {
        var result = executor.run(WxPaths.listFilesCommand(hash))
        if (result.exitCode != 0 && result.stderr.contains("stat", ignoreCase = true)) {
            result = executor.run(WxPaths.listFilesCommand(hash, useWcForSize = true))
        }
        if (result.exitCode != 0) {
            throw IllegalStateException(result.stderr.ifBlank { "缓存文件读取失败" })
        }
        result.stdout.lines()
            .mapNotNull { parseLine(hash, it) }
    }

    private fun parseLine(hash: String, line: String): CacheFile? {
        val parts = line.split('\t')
        if (parts.size < 4) return null
        val name = parts[0].trim()
        if (!WxPaths.isValidCacheFileName(name)) return null
        val size = parts[1].trim().toLongOrNull() ?: return null
        val modifiedAt = parts[2].trim().toLongOrNull() ?: 0L
        val type = FileTypeDetector.detect(parts[3].trim())
        return CacheFile(hash, name, size, modifiedAt, type)
    }
}
