package top.aico.wxcacheviewer.data.media

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import top.aico.wxcacheviewer.data.log.AppLog
import top.aico.wxcacheviewer.data.root.RootCommandExecutor
import top.aico.wxcacheviewer.data.root.WxPaths
import top.aico.wxcacheviewer.domain.model.CacheFile
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
 */
class MediaPreparer(
    context: Context,
    private val executor: RootCommandExecutor
) {
    private val rootDir = File(context.cacheDir, DIR_NAME).apply { mkdirs() }
    private val semaphore = Semaphore(MAX_CONCURRENT)
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun prepare(file: CacheFile): File? = withContext(Dispatchers.IO) {
        val mutex = locks.getOrPut(file.key) { Mutex() }
        mutex.withLock {
            val target = targetFile(file)
            if (target.isFile && target.length() == file.size) {
                target.setLastModified(System.currentTimeMillis())
                return@withLock target
            }
            semaphore.withPermit {
                target.delete()
                val source = SourceRegistry.byId(file.sourceId)
                val result = executor.run(
                    WxPaths.copySourceFileCommand(
                        file.accountHash,
                        source,
                        file.relativePath,
                        target.absolutePath
                    ),
                    COPY_TIMEOUT_MS
                )
                if (result.exitCode != 0 || !target.isFile) {
                    AppLog.log(
                        "media",
                        "copy failed exit=${result.exitCode} ${file.relativePath} ${result.stderr.take(160)}"
                    )
                    target.delete()
                    null
                } else {
                    prune()
                    target
                }
            }
        }
    }

    private fun targetFile(file: CacheFile): File {
        val dir = File(rootDir, file.sourceId).apply { mkdirs() }
        val name = "${digest("${file.key}#${file.size}")}.${file.type.extension}"
        return File(dir, name)
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
    }
}
