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
import top.aico.wxcacheviewer.domain.model.FileType
import top.aico.wxcacheviewer.domain.model.extension
import top.aico.wxcacheviewer.domain.source.CacheSource
import top.aico.wxcacheviewer.domain.source.SourceRegistry
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 一次列表的结果；递归源文件过多时会截断，[truncated] 用于界面提示。 */
data class SourceListing(
    val files: List<CacheFile>,
    val truncated: Boolean = false
)

class CacheRepository(private val executor: RootCommandExecutor) {

    suspend fun checkRoot(): RootState = executor.checkRoot()

    suspend fun discoverAccounts(): List<Account> = withContext(Dispatchers.IO) {
        val listing = executor.run(WxPaths.discoverAccountsCommand())
        if (listing.exitCode != 0 && listing.stdout.isBlank()) {
            throw IllegalStateException(listing.stderr.ifBlank { "账号目录读取失败" })
        }
        AccountDiscoveryParser.parse(listing.stdout)
    }

    /** 列出多个缓存源；递归源合并后按修改时间倒序截断，避免上千个文件拖垮首屏。 */
    suspend fun listFiles(hash: String, sources: List<CacheSource>): SourceListing =
        withContext(Dispatchers.IO) {
            val collected = ArrayList<CacheFile>()
            sources.forEach { source -> collected += listSource(hash, source) }
            if (sources.none { it.recursive }) return@withContext SourceListing(collected)
            val ordered = collected.sortedByDescending { it.modifiedAt }
            SourceListing(ordered.take(MAX_RECURSIVE_ITEMS), ordered.size > MAX_RECURSIVE_ITEMS)
        }

    /** 旧签名：只列视频号缓存。 */
    suspend fun listFiles(hash: String): List<CacheFile> =
        listFiles(hash, listOf(SourceRegistry.FINDER_VIDEO)).files

    private suspend fun listSource(hash: String, source: CacheSource): List<CacheFile> {
        var result = executor.run(
            WxPaths.listSourceFilesCommand(hash, source),
            source.listTimeoutMs
        )
        // 部分机型的 stat 不支持 -c，此时改用 wc -c 取大小重试一次。
        if (result.exitCode != 0 && !source.recursive &&
            result.stderr.contains("stat", ignoreCase = true)
        ) {
            result = executor.run(
                WxPaths.listSourceFilesCommand(hash, source, useWcForSize = true),
                source.listTimeoutMs
            )
        }
        if (result.exitCode != 0) {
            throw IllegalStateException(result.stderr.ifBlank { "缓存文件读取失败" })
        }
        return result.stdout.lines().mapNotNull { parseLine(source, hash, it) }
    }

    /**
     * 一行六个字段：文件名、字节数、mtime、文件头 hex、相对路径、是否有同名 jpg 首帧。
     * 第 5、6 字段缺失时回退到文件名，因此旧的四字段输出仍然可用。
     */
    private fun parseLine(source: CacheSource, hash: String, line: String): CacheFile? {
        val parts = line.split('\t')
        if (parts.size < 4) return null
        val name = parts[0].trim()
        val size = parts[1].trim().toLongOrNull() ?: return null
        val modifiedAt = parts[2].trim().toLongOrNull() ?: 0L
        val relativePath = parts.getOrNull(4)?.trim()?.takeIf { it.isNotEmpty() } ?: name
        if (name != relativePath.substringAfterLast('/')) return null
        if (!SourceRegistry.isValidRelativePath(source, relativePath)) return null
        val header = parts[3].trim()
        val type = if (header.isEmpty() || header == "-") {
            source.typeHint(relativePath) ?: FileType.UNKNOWN
        } else {
            FileTypeDetector.detect(header)
        }
        return CacheFile(
            accountHash = hash,
            name = name,
            size = size,
            modifiedAt = modifiedAt,
            type = type,
            relativePath = relativePath,
            sourceId = source.id,
            hasThumbnail = parts.getOrNull(5)?.trim() == "1"
        )
    }

    suspend fun deleteFile(file: CacheFile): Unit = withContext(Dispatchers.IO) {
        val source = SourceRegistry.byId(file.sourceId)
        val result = executor.run(
            WxPaths.deleteSourceFileCommand(file.accountHash, source, file.relativePath)
        )
        if (result.exitCode != 0 || !result.stdout.contains(OK_MARK)) {
            throw IllegalStateException(result.stderr.ifBlank { "删除失败" })
        }
    }

    /**
     * 通过 root 把缓存文件复制到用户可访问的目录。
     * App 自身无权写公共目录，root 可以；目标目录由调用方决定，便于测试。
     */
    suspend fun exportFile(file: CacheFile, destinationDir: File): File = withContext(Dispatchers.IO) {
        val source = SourceRegistry.byId(file.sourceId)
        val destination = File(destinationDir, exportFileName(file))
        val result = executor.run(
            WxPaths.exportSourceFileCommand(
                file.accountHash,
                source,
                file.relativePath,
                destination.absolutePath
            )
        )
        if (result.exitCode != 0 || !result.stdout.contains(OK_MARK)) {
            throw IllegalStateException(result.stderr.ifBlank { "导出失败" })
        }
        destination
    }

    /**
     * 导出名尽量可读：`<源前缀>_年月日_时分秒_<相对路径哈希 8 位>.<扩展名>`。
     * 末段用相对路径哈希，保证不同子目录下的同名文件不会互相覆盖。
     */
    private fun exportFileName(file: CacheFile): String {
        val source = SourceRegistry.byId(file.sourceId)
        val timeMillis = if (file.modifiedAt > 0L) file.modifiedAt * 1000 else System.currentTimeMillis()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(timeMillis))
        return "${source.exportTag}_${stamp}_${shortDigest(file.relativePath)}.${file.type.extension}"
    }

    private fun shortDigest(value: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(8)

    private companion object {
        const val OK_MARK = "OK"

        /** 递归源单次最多展示的文件数。 */
        const val MAX_RECURSIVE_ITEMS = 1500
    }
}
