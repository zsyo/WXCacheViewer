package top.aico.wxcacheviewer.data.playback

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.aico.wxcacheviewer.data.log.AppLog
import top.aico.wxcacheviewer.data.root.RootCommandExecutor
import top.aico.wxcacheviewer.data.root.WxPaths
import top.aico.wxcacheviewer.domain.model.CacheFile
import top.aico.wxcacheviewer.domain.source.SourceRegistry
import java.io.File

/**
 * 把微信私有目录里的缓存视频复制到应用可读的缓存目录。
 * 微信缓存文件没有扩展名，复制时补上 .mp4 才能被播放器按容器格式识别。
 */
class VideoPreparer(private val executor: RootCommandExecutor) {

    suspend fun prepare(context: Context, file: CacheFile): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, DIR_NAME).apply { mkdirs() }
        val target = File(dir, "${targetName(file)}.mp4")

        if (target.isFile && target.length() == file.size) {
            target.setLastModified(System.currentTimeMillis())
            return@withContext target
        }

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
        if (result.exitCode != 0 || !target.isFile || target.length() != file.size) {
            AppLog.log("player", "copy failed exit=${result.exitCode} err=${result.stderr.take(200)}")
            target.delete()
            throw IllegalStateException(
                result.stderr.ifBlank { "复制视频文件失败，请检查 root 授权" }
            )
        }
        prune(dir, keep = target.name)
        target
    }

    /** 播放缓存文件名要跨源唯一，递归源里不同目录可能同名。 */
    private fun targetName(file: CacheFile): String =
        "${file.sourceId}_${file.relativePath.replace('/', '_')}"

    /** 只保留最近若干个已准备好的文件，避免缓存无限增长。 */
    private fun prune(dir: File, keep: String) {
        val files = dir.listFiles()
            ?.filter { it.isFile && it.name != keep }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        files.drop(MAX_CACHED_FILES - 1).forEach { it.delete() }
    }

    private companion object {
        const val DIR_NAME = "playback"
        const val COPY_TIMEOUT_MS = 180_000L
        const val MAX_CACHED_FILES = 3
    }
}
