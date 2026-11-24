package top.aico.wxcacheviewer.data.emoji

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.aico.wxcacheviewer.data.log.AppLog
import top.aico.wxcacheviewer.data.root.RootCommandExecutor
import top.aico.wxcacheviewer.data.root.WxPaths
import top.aico.wxcacheviewer.domain.ExportNaming
import top.aico.wxcacheviewer.domain.emoji.EmojiKeyProvider
import top.aico.wxcacheviewer.domain.model.CacheFile
import top.aico.wxcacheviewer.domain.model.FileType
import top.aico.wxcacheviewer.domain.model.extension
import top.aico.wxcacheviewer.domain.source.SourceRegistry
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * 表情的导出。
 *
 * 导出的必须是能直接打开的成品，不能把微信目录里的原始字节复制出去，因此流程是：
 * root 复制到应用私有目录 → 解开前 1KB → 借 root 落到用户选的导出目录。
 * 文件名按最终内容补扩展名：普通表情是 GIF/PNG/JPEG，wxgf 则重编码成动图 GIF。
 */
class EmojiPreparer(
    context: Context,
    private val executor: RootCommandExecutor,
    private val emojiKeys: EmojiKeyProvider
) {
    private val tempDir = File(context.cacheDir, DIR_NAME)

    suspend fun export(file: CacheFile, destinationDir: File): File = withContext(Dispatchers.IO) {
        val local = decrypted(file)
        val destination = ExportNaming.uniqueDestination(
            destinationDir,
            ExportNaming.nameFor(file, local.extension)
        )
        val result = executor.run(
            WxPaths.exportLocalFileCommand(local.absolutePath, destination.absolutePath)
        )
        if (result.exitCode != 0 || !result.stdout.contains(OK_MARK)) {
            throw IllegalStateException(result.stderr.ifBlank { "导出失败" })
        }
        destination
    }

    /**
     * 复制并解密；文件名带文件大小，内容变化后缓存自然失效。
     *
     * root 复制出来的原件属主是 root、只读，所以不在原件上改写，而是由应用读出后
     * 另写一份自己的产物（属主与 SELinux 标签都正确），再交给 root 导出。
     */
    private suspend fun decrypted(file: CacheFile): File {
        tempDir.mkdirs()
        val cached = findCached(file)
        if (cached != null) {
            cached.setLastModified(System.currentTimeMillis())
            return cached
        }
        val source = SourceRegistry.byId(file.sourceId)
        val raw = File(tempDir, "${cacheId(file)}.raw")
        raw.delete()
        val result = executor.run(
            WxPaths.copySourceFileCommand(
                file.accountHash,
                source,
                file.relativePath,
                raw.absolutePath
            ),
            COPY_TIMEOUT_MS
        )
        if (result.exitCode != 0 || !raw.isFile) {
            AppLog.log(
                "emoji",
                "copy failed exit=${result.exitCode} ${file.relativePath} ${result.stderr.take(160)}"
            )
            raw.delete()
            throw IllegalStateException(
                result.stderr.ifBlank { "复制表情文件失败，请检查 root 授权" }
            )
        }
        val target = try {
            val key = if (file.encrypted) emojiKeys.keyFor(file.accountHash) else null
            if (file.encrypted && key == null) throw IllegalStateException("表情解密失败")
            if (file.type == FileType.WXGF) {
                // 微信里这些表情是动图，导出成静帧等于丢内容，这里重编码成 GIF
                val gif = File(tempDir, "${cacheId(file)}.${FileType.WXGF.extension}")
                if (!EmojiContent.materializeAnimated(raw.readBytes(), key, file.encrypted, gif)) {
                    throw IllegalStateException("表情动图转换失败")
                }
                gif
            } else {
                val content = EmojiContent.materialize(raw.readBytes(), key, file.encrypted, tempDir)
                    ?: throw IllegalStateException("表情解密失败")
                val extension = content.extension ?: file.type.extension
                File(tempDir, "${cacheId(file)}.$extension").also { it.writeBytes(content.bytes) }
            }
        } catch (e: Exception) {
            AppLog.log("emoji", "decrypt failed ${file.relativePath}: ${e.message}")
            throw IllegalStateException("表情解密失败", e)
        } finally {
            raw.delete()
        }
        prune()
        return target
    }

    /** 产物扩展名跟源类型不完全一致（wxgf → gif），按「id + 期望扩展名」精确找已有的那份。 */
    private fun findCached(file: CacheFile): File? {
        val name = "${cacheId(file)}.${file.type.extension}"
        return tempDir.listFiles()?.firstOrNull { it.isFile && it.name == name }
    }

    /** wxgf 的产物名带上编码版本，帧率/格式调整后旧 GIF 自动失效。 */
    private fun cacheId(file: CacheFile): String {
        val tag = if (file.type == FileType.WXGF) "#${EmojiContent.CACHE_TAG}" else ""
        return MessageDigest.getInstance("SHA-1")
            .digest("${file.key}#${file.size}$tag".toByteArray(Charsets.UTF_8))
            .joinToString("") { String.format(Locale.US, "%02x", it) }
            .take(16)
    }

    /** 只保留最近使用的若干个中间文件，避免私有目录无限增长。 */
    private fun prune() {
        val files = tempDir.listFiles()?.filter { it.isFile } ?: return
        files.sortedByDescending { it.lastModified() }
            .drop(MAX_CACHED_FILES)
            .forEach { it.delete() }
    }

    private companion object {
        const val DIR_NAME = "emoji_decrypted"
        const val COPY_TIMEOUT_MS = 60_000L
        const val MAX_CACHED_FILES = 60
        const val OK_MARK = "OK"
        const val RAW_SUFFIX = ".raw"
    }
}
