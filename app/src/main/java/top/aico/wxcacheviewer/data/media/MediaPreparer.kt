package top.aico.wxcacheviewer.data.media

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import top.aico.wxcacheviewer.data.emoji.EmojiContent
import top.aico.wxcacheviewer.data.log.AppLog
import top.aico.wxcacheviewer.data.root.RootCommandExecutor
import top.aico.wxcacheviewer.data.root.WxPaths
import top.aico.wxcacheviewer.domain.emoji.EmojiKeyProvider
import top.aico.wxcacheviewer.domain.model.CacheFile
import top.aico.wxcacheviewer.domain.model.FileType
import top.aico.wxcacheviewer.domain.model.extension
import top.aico.wxcacheviewer.domain.source.SourceRegistry
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 把图片类缓存复制到应用私有目录，供 Coil 直接按 File 加载。
 *
 * root 复制是挂起调用，无法在 Coil 的同步 Fetcher 里完成，因此拆成「先复制、再加载」两步。
 * 目标文件名带文件大小，内容变化后 key 自然失效；并发限制在 [MAX_CONCURRENT] 个 su 进程。
 *
 * 表情目录里的裸 md5 文件只有前 1KB 被加密，复制完成后解开头部就得到可解码的
 * GIF/PNG/JPEG；解出来是 wxgf 的还要再解出首帧、转成 PNG 才能给 Coil。
 */
class MediaPreparer(
    context: Context,
    private val executor: RootCommandExecutor,
    private val emojiKeys: EmojiKeyProvider = EmojiKeyProvider.None
) {
    private val rootDir = File(context.cacheDir, DIR_NAME).apply { mkdirs() }
    private val semaphore = Semaphore(MAX_CONCURRENT)
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun prepare(file: CacheFile): File? = withContext(Dispatchers.IO) {
        val mutex = locks.getOrPut(file.key) { Mutex() }
        mutex.withLock {
            val target = targetFile(file)
            // 加密表情与 wxgf 的产物长度跟原件对不上（wxgf 会转成 PNG），只能看文件在不在。
            val fresh = if (file.encrypted || file.type == FileType.WXGF) {
                target.isFile
            } else {
                target.isFile && target.length() == file.size
            }
            if (fresh) {
                target.setLastModified(System.currentTimeMillis())
                return@withLock target
            }
            semaphore.withPermit {
                // 复制/解密一旦开始就做完：列表滚动时卡片会离开组合、协程被取消，
                // 半途而废等于每滚一次都白做，回来还是占位。
                withContext(NonCancellable) {
                    target.delete()
                    val ready = materialize(file, target)
                    if (ready) {
                        prune()
                        target
                    } else {
                        target.delete()
                        null
                    }
                }
            }
        }
    }

    private fun targetFile(file: CacheFile): File {
        val dir = File(rootDir, file.sourceId).apply { mkdirs() }
        val marker = if (file.encrypted) DECRYPTED_MARK else ""
        val name = "${digest("${file.key}#${file.size}")}$marker.${extensionOf(file)}"
        return File(dir, name)
    }

    /** root 复制原件到应用私有目录，仍保持 0644 只读。 */
    private suspend fun copyFromRoot(file: CacheFile, destination: File): Boolean {
        val source = SourceRegistry.byId(file.sourceId)
        val result = executor.run(
            WxPaths.copySourceFileCommand(
                file.accountHash,
                source,
                file.relativePath,
                destination.absolutePath
            ),
            COPY_TIMEOUT_MS
        )
        if (result.exitCode != 0 || !destination.isFile) {
            AppLog.log(
                "media",
                "copy failed exit=${result.exitCode} ${file.relativePath} ${result.stderr.take(160)}"
            )
            return false
        }
        return true
    }

    /**
     * 先 root 复制一份只读原件，再由应用读出、解开头部（必要时再解出 wxgf 首帧），
     * 最后写成自己的文件。
     *
     * 不在原件上就地改写，是因为 root 复制出来的文件属主是 root，0644 没写权限；
     * 放宽成 0666 也可能被 SELinux 标签挡住。让应用自己创建产物最稳。
     */
    private suspend fun materialize(file: CacheFile, target: File): Boolean {
        val raw = File(target.parentFile, target.name + RAW_SUFFIX)
        raw.delete()
        if (!copyFromRoot(file, raw)) {
            raw.delete()
            return false
        }
        val ok = try {
            val key = if (file.encrypted) emojiKeys.keyFor(file.accountHash) else null
            if (file.encrypted && key == null) {
                AppLog.log("media", "decrypt key missing ${file.relativePath}")
                false
            } else {
                val content = EmojiContent.materialize(
                    raw.readBytes(),
                    key,
                    file.encrypted,
                    target.parentFile ?: raw.parentFile ?: rootDir
                )
                if (content == null) {
                    AppLog.log("media", "emoji content failed ${file.relativePath}")
                    false
                } else {
                    target.writeBytes(content.bytes)
                    true
                }
            }
        } catch (e: Exception) {
            AppLog.log("media", "decrypt failed ${file.relativePath}: ${e.message}")
            false
        }
        raw.delete()
        return ok
    }

    /**
     * 缓存本体多数没有扩展名，靠类型补；聊天文件自带扩展名，必须原样保留，
     * 否则打开时系统按扩展名找应用会找不到（docx/xlsx 这类尤其明显）。
     */
    private fun extensionOf(file: CacheFile): String {
        // wxgf 落盘的已经是解出来的 PNG，不能再按源类型补成 wxgf。
        if (file.type == FileType.WXGF) return EmojiContent.PNG_EXTENSION
        val dot = file.name.lastIndexOf('.')
        if (dot in 1 until file.name.lastIndex) {
            val candidate = file.name.substring(dot + 1).lowercase()
            if (candidate.length <= 5 && candidate.all { it.isLetterOrDigit() }) return candidate
        }
        return file.type.extension
    }

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)

    /** 只保留最近使用的若干张图片，避免私有目录无限增长。 */
    private fun prune() {
        val files = rootDir.walkTopDown()
            .filter { it.isFile }
            .sortedByDescending { it.lastModified() }
            .toList()
        files.drop(MAX_CACHED_FILES).forEach { it.delete() }
    }

    private companion object {
        const val DIR_NAME = "media"
        const val MAX_CONCURRENT = 3
        const val MAX_CACHED_FILES = 300
        const val COPY_TIMEOUT_MS = 60_000L

        /** 解密产物的文件名标记：与「原件直通」区分开，避免两种内容共用同一个缓存名。 */
        const val DECRYPTED_MARK = ".dec"

        /** 加密表情的原件临时后缀，解密完成后删除。 */
        const val RAW_SUFFIX = ".raw"
    }
}
