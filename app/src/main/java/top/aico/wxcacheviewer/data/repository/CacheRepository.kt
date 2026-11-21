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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    suspend fun deleteFile(file: CacheFile): Unit = withContext(Dispatchers.IO) {
        val result = executor.run(WxPaths.deleteFileCommand(file.accountHash, file.name))
        if (result.exitCode != 0 || !result.stdout.contains(OK_MARK)) {
            throw IllegalStateException(result.stderr.ifBlank { "删除失败" })
        }
    }

    /**
     * 通过 root 把缓存文件复制到用户可访问的目录。
     * App 自身无权写公共目录，root 可以；目标目录由调用方决定，便于测试。
     */
    suspend fun exportFile(file: CacheFile, destinationDir: File): File = withContext(Dispatchers.IO) {
        val destination = File(destinationDir, exportFileName(file))
        val result = executor.run(
            WxPaths.exportFileCommand(file.accountHash, file.name, destination.absolutePath)
        )
        if (result.exitCode != 0 || !result.stdout.contains(OK_MARK)) {
            throw IllegalStateException(result.stderr.ifBlank { "导出失败" })
        }
        destination
    }

    /** 导出名尽量可读：wxvideo_年月日_时分秒_缓存名后 8 位.mp4，末段保证同秒导出也不重名。 */
    private fun exportFileName(file: CacheFile): String {
        val timeMillis = if (file.modifiedAt > 0L) file.modifiedAt * 1000 else System.currentTimeMillis()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(timeMillis))
        return "wxvideo_${stamp}_${file.name.takeLast(8)}.mp4"
    }

    private companion object {
        const val OK_MARK = "OK"
    }
}
