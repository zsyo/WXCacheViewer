package top.aico.wxcacheviewer.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.aico.wxcacheviewer.data.log.AppLog
import top.aico.wxcacheviewer.data.root.AccountDiscoveryParser
import top.aico.wxcacheviewer.data.root.RootCommandExecutor
import top.aico.wxcacheviewer.data.root.RootState
import top.aico.wxcacheviewer.data.root.WxPaths
import top.aico.wxcacheviewer.domain.ExportNaming
import top.aico.wxcacheviewer.domain.FileTypeDetector
import top.aico.wxcacheviewer.domain.emoji.EmojiCrypto
import top.aico.wxcacheviewer.domain.emoji.EmojiKeyProvider
import top.aico.wxcacheviewer.domain.model.Account
import top.aico.wxcacheviewer.domain.model.CacheFile
import top.aico.wxcacheviewer.domain.model.FileType
import top.aico.wxcacheviewer.domain.model.extension
import top.aico.wxcacheviewer.domain.source.CacheSource
import top.aico.wxcacheviewer.domain.source.SourceRegistry
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** 一次列表的结果；递归源文件过多时会截断，[truncated] 用于界面提示。 */
data class SourceListing(
    val files: List<CacheFile>,
    val truncated: Boolean = false
)

/**
 * 分页游标：第几个源的第几个位置。
 *
 * 位置的含义随源类型变化——递归源是「下一个一级子目录的序号」，
 * 扁平源是「已经取过的文件条数」。游标对界面是不透明标识，原样带回即可。
 */
data class PageCursor(val sourceIndex: Int, val position: Int)

/**
 * 一页结果。[nextCursor] 为 null 表示已经到底；[error] 非空表示这一页读取失败，
 * 但界面可以先展示已拿到的内容，不必整页报错。
 */
data class SourcePage(
    val files: List<CacheFile>,
    val nextCursor: PageCursor? = null,
    val error: String? = null
)

class CacheRepository(
    private val executor: RootCommandExecutor,
    /** 表情解密密钥；拿不到时表情页退回只展示明文文件。 */
    private val emojiKeys: EmojiKeyProvider = EmojiKeyProvider.None
) {

    /** 顶层子目录列表只在内存里缓存，下拉刷新时清空。多个加载协程会并发读写，用并发容器。 */
    private val subDirCache = ConcurrentHashMap<String, List<String>>()

    /** 一次分页取到的切片，[nextPosition] 为空表示这个源已经取完。 */
    private data class Slice(
        val files: List<CacheFile>,
        val nextPosition: Int?,
        val error: String?
    )

    fun invalidate() = subDirCache.clear()

    suspend fun checkRoot(): RootState = executor.checkRoot()

    suspend fun discoverAccounts(): List<Account> = withContext(Dispatchers.IO) {
        val listing = executor.run(WxPaths.discoverAccountsCommand())
        if (listing.exitCode != 0 && listing.stdout.isBlank()) {
            throw IllegalStateException(listing.stderr.ifBlank { "账号目录读取失败" })
        }
        AccountDiscoveryParser.parse(listing.stdout)
    }

    /**
     * 取一页文件。[cursor] 传 null 表示第一页。
     *
     * 两类源都分页，只是切法不同：递归源（聊天图片、朋友圈）一次扫一个一级子目录；
     * 扁平源（视频号、聊天视频、表情）按文件名倒序切片，一页只 `stat` 几十个文件。
     * 这样首屏耗时与缓存总量脱钩，切标签页不再需要等一次全量扫描。
     */
    suspend fun loadPage(
        hash: String,
        sources: List<CacheSource>,
        cursor: PageCursor?
    ): SourcePage = withContext(Dispatchers.IO) {
        var index = cursor?.sourceIndex ?: 0
        var position = cursor?.position ?: 0
        var skipped = 0
        // 表情的加密文件需要密钥才能判断真实类型；没有密钥就整类跳过，避免点开是乱码。
        val emojiKey = sources
            .firstOrNull { it.encryptedName != null }
            ?.let { emojiKeys.keyFor(hash) }

        while (index < sources.size) {
            val source = sources[index]
            val slice = if (source.recursive) {
                dirSlice(hash, source, position)
            } else {
                flatSlice(hash, source, position, emojiKey)
            }
            val next = when {
                slice.nextPosition != null -> PageCursor(index, slice.nextPosition)
                index + 1 < sources.size -> PageCursor(index + 1, 0)
                else -> null
            }

            if (slice.files.isNotEmpty()) {
                // 即使拿到了部分文件，也要把本页的错误（如超时）交给界面提示，
                // 让用户知道这一页可能不完整。游标已按实际读取量推进，可继续翻页。
                return@withContext SourcePage(slice.files, next, slice.error)
            }
            if (cursor == null && slice.error != null) {
                return@withContext SourcePage(emptyList(), null, slice.error)
            }
            if (next == null) return@withContext SourcePage(emptyList(), null, null)
            // 空页继续往后翻，但别让它无休止地跳（例如整棵树都是空目录）
            if (skipped++ >= MAX_SKIPPED_SLICES) {
                return@withContext SourcePage(emptyList(), next, null)
            }
            index = next.sourceIndex
            position = next.position
        }
        SourcePage(emptyList(), null, null)
    }

    /**
     * 一次性列完（供测试与「全量」场景使用）。会连续翻页，页数有上限。
     */
    suspend fun listFiles(hash: String, sources: List<CacheSource>): SourceListing =
        withContext(Dispatchers.IO) {
            invalidate()
            val files = ArrayList<CacheFile>()
            var cursor: PageCursor? = null
            var pages = 0
            do {
                val page = loadPage(hash, sources, cursor)
                files += page.files
                cursor = page.nextCursor
                pages++
                page.error?.let { throw IllegalStateException(it) }
            } while (cursor != null && pages < MAX_DRAIN_PAGES)
            SourceListing(files, truncated = cursor != null)
        }

    /** 旧签名：只列视频号缓存。 */
    suspend fun listFiles(hash: String): List<CacheFile> =
        listFiles(hash, listOf(SourceRegistry.FINDER_VIDEO)).files

    /** 扁平源的一页：只对本页这几十个文件执行 stat。 */
    private suspend fun flatSlice(
        hash: String,
        source: CacheSource,
        offset: Int,
        emojiKey: ByteArray?
    ): Slice {
        val limit = source.pageSize
        var result = executor.run(
            WxPaths.listSourcePageCommand(hash, source, offset, limit),
            source.listTimeoutMs
        )
        // 部分机型的 stat 不支持 -c，此时改用 wc -c 取大小重试一次。
        if (result.exitCode != 0 && result.stderr.contains("stat", ignoreCase = true)) {
            result = executor.run(
                WxPaths.listSourcePageCommand(hash, source, offset, limit, useWcForSize = true),
                source.listTimeoutMs
            )
        }
        val lines = result.stdout.lines().filter { it.isNotBlank() }
        val files = lines.mapNotNull { parseLine(source, hash, it, emojiKey) }
        if (source.encryptedName != null && files.size < lines.size) {
            // 加密表情可能成页被跳过（无密钥 / wxgf），留一行日志便于排查「表情变少了」。
            AppLog.log(
                "emoji",
                "page ${source.id}@$offset kept=${files.size}/${lines.size} " +
                    "hasKey=${emojiKey != null}"
            )
        }

        if (result.exitCode != 0) {
            AppLog.log(
                "repo",
                "flat page failed ${source.id}@$offset exit=${result.exitCode} " +
                    "timedOut=${result.timedOut} err=${result.stderr.take(160)}"
            )
            val message = when {
                result.timedOut -> "读取超时，本页可能不完整"
                else -> result.stderr.ifBlank { "缓存文件读取失败" }
            }
            // 超时只输出了前面一部分，必须按实际拿到的行数推进游标。
            // 原先按 offset + limit 推进会把没读到的文件永久跳过，这一会话再也翻不到。
            if (files.isNotEmpty()) {
                return Slice(files.sortedByDescending { it.modifiedAt }, offset + lines.size, message)
            }
            return Slice(emptyList(), null, message)
        }
        if (files.isEmpty() && lines.isNotEmpty() && !allEncrypted(source, lines)) {
            AppLog.log(
                "repo",
                "flat page parse failed ${source.id}@$offset lines=${lines.size} " +
                    "sample=${lines.first().take(160)}"
            )
            return Slice(emptyList(), null, "文件列表格式异常")
        }
        // 本页取满说明后面可能还有；不满就是最后一页
        val next = if (lines.size >= limit) offset + limit else null
        return Slice(files.sortedByDescending { it.modifiedAt }, next, null)
    }

    /** 递归源的一页：只扫一个一级子目录。 */
    private suspend fun dirSlice(hash: String, source: CacheSource, dirIndex: Int): Slice {
        val dirs = subDirs(hash, source)
        if (dirIndex >= dirs.size) return Slice(emptyList(), null, null)
        val page = listDirPage(hash, source, dirs[dirIndex])
        val next = if (dirIndex + 1 < dirs.size) dirIndex + 1 else null
        return Slice(page.files, next, page.error)
    }

    /**
     * 递归源的一级子目录，按修改时间倒序。一次 `stat` 就能拿到几百个目录的时间，
     * 拿不到（老版本 toybox 的 stat 不支持 %n）时退回只取目录名。
     */
    private suspend fun subDirs(hash: String, source: CacheSource): List<String> {
        val cacheKey = "$hash:${source.id}"
        subDirCache[cacheKey]?.let { return it }

        val timed = executor.run(WxPaths.listSourceSubDirsCommand(hash, source), source.listTimeoutMs)
        val dirs = timed.stdout.lines().mapNotNull { line ->
            val space = line.indexOf(' ')
            if (space <= 0) return@mapNotNull null
            val mtime = line.substring(0, space).toLongOrNull() ?: return@mapNotNull null
            val name = line.substring(space + 1).trim().trimEnd('/').substringAfterLast('/')
            if (!SourceRegistry.isSafeSegment(name)) null else name to mtime
        }.sortedByDescending { it.second }.map { it.first }

        val resolved = if (dirs.isNotEmpty()) {
            dirs
        } else {
            // 兜底：只列名字，顺序按文件系统默认
            executor.run(WxPaths.listSourceSubDirNamesCommand(hash, source), source.listTimeoutMs)
                .stdout.lines()
                .map { it.trim().trimEnd('/').substringAfterLast('/') }
                .filter { SourceRegistry.isSafeSegment(it) }
        }
        AppLog.log("repo", "subdirs ${source.id}: ${resolved.size}")
        subDirCache[cacheKey] = resolved
        return resolved
    }

    /** 扫描一个一级子目录，作为一页。失败只记日志，不影响其它页。 */
    private suspend fun listDirPage(
        hash: String,
        source: CacheSource,
        dirName: String
    ): DirPage {
        val result = executor.run(
            WxPaths.listSourceDirPageCommand(hash, source, dirName),
            source.listTimeoutMs
        )
        val lines = result.stdout.lines().filter { it.isNotBlank() }
        // 递归源（聊天图片、朋友圈、语音）没有加密文件，不需要密钥。
        val files = lines.mapNotNull { parseLine(source, hash, it, null) }

        if (result.exitCode != 0) {
            AppLog.log(
                "repo",
                "page failed ${source.id}/$dirName exit=${result.exitCode} " +
                    "timedOut=${result.timedOut} err=${result.stderr.take(160)}"
            )
            val message = when {
                result.timedOut -> "读取超时，本页可能不完整"
                else -> result.stderr.ifBlank { "缓存文件读取失败" }
            }
            // 超时也可能已经读到一部分，能解析就先用着；但要带错误提示，
            // 让用户知道这个目录没扫完，可下拉重试。
            if (files.isNotEmpty()) {
                return DirPage(files.sortedByDescending { it.modifiedAt }, message)
            }
            return DirPage(emptyList(), message)
        }

        // 命令成功却有输出、却一个都解析不出来：shell 侧字段与约定不一致。
        // 不能当作空目录跳过，否则界面会显示「没有缓存」，把真正的 bug 藏起来。
        if (files.isEmpty() && lines.isNotEmpty()) {
            AppLog.log(
                "repo",
                "page parse failed ${source.id}/$dirName lines=${lines.size} " +
                    "sample=${lines.first().take(160)}"
            )
            return DirPage(emptyList(), "文件列表格式异常")
        }
        return DirPage(files.sortedByDescending { it.modifiedAt }, null)
    }

    private data class DirPage(val files: List<CacheFile>, val error: String?)

    /**
     * 一行六个字段：文件名、字节数、mtime、文件头 hex、相对路径、是否有同名 jpg 首帧。
     * 第 5、6 字段缺失时回退到文件名，因此旧的四字段输出仍然可用。
     *
     * 表情目录里的裸 32 位 md5 是「只有头部被加密」的文件：拿列目录时已经读到的文件头
     * 当场解密来判断真实类型。拿不到密钥或解不出类型时返回 null，宁可不展示，
     * 也不给用户一张点开是乱码的卡片；wxgf 能自己解出首帧，所以照常列出来。
     */
    private fun parseLine(
        source: CacheSource,
        hash: String,
        line: String,
        emojiKey: ByteArray?
    ): CacheFile? {
        val parts = line.split('\t')
        if (parts.size < 4) return null
        val name = parts[0].trim()
        val size = parts[1].trim().toLongOrNull() ?: return null
        val modifiedAt = parts[2].trim().toLongOrNull() ?: 0L
        val relativePath = parts.getOrNull(4)?.trim()?.takeIf { it.isNotEmpty() } ?: name
        if (name != relativePath.substringAfterLast('/')) return null
        if (!SourceRegistry.isValidRelativePath(source, relativePath)) return null
        val header = parts[3].trim()
        val encrypted = source.encryptedName?.matches(name) == true
        val type = if (encrypted) {
            val key = emojiKey ?: return null
            val plain = EmojiCrypto.decryptHeaderHex(header, key) ?: return null
            FileTypeDetector.detect(plain).takeIf { it != FileType.UNKNOWN } ?: return null
        } else if (header.isEmpty() || header == "-") {
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
            hasThumbnail = parts.getOrNull(5)?.trim() == "1",
            encrypted = encrypted
        )
    }

    /** 本页每一行都是加密表情文件名——这类行被整页跳过属于正常，不是格式异常。 */
    private fun allEncrypted(source: CacheSource, lines: List<String>): Boolean {
        val shape = source.encryptedName ?: return false
        return lines.all { shape.matches(it.substringBefore('\t').trim()) }
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
        // 聊天文件保留原名，重名时加序号，不能直接覆盖用户之前导出的同名文件
        val destination = ExportNaming.uniqueDestination(
            destinationDir,
            ExportNaming.nameFor(file, file.type.extension)
        )
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

    private companion object {
        const val OK_MARK = "OK"

        /** 全量列举时的翻页上限，防止异常目录导致死循环。 */
        const val MAX_DRAIN_PAGES = 600

        /** 一次加载里最多跳过多少个空页（空子目录、超出末尾），避免空目录拖着界面干等。 */
        const val MAX_SKIPPED_SLICES = 12
    }
}
