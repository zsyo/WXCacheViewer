package top.aico.wxcacheviewer.data.thumbnail

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import top.aico.wxcacheviewer.data.log.AppLog
import top.aico.wxcacheviewer.data.root.RootCommandExecutor
import top.aico.wxcacheviewer.data.root.WxPaths
import top.aico.wxcacheviewer.domain.model.CacheFile
import top.aico.wxcacheviewer.domain.model.FileType
import java.io.File

class ThumbnailLoader(
    context: Context,
    private val executor: RootCommandExecutor
) {
    private val cache = LruCache<String, Bitmap>(MAX_CACHE_BYTES)
    private val tempDir = File(context.cacheDir, "thumb_tmp").apply { mkdirs() }

    suspend fun load(accountHash: String, file: CacheFile): Bitmap? = withContext(Dispatchers.IO) {
        if (file.type != FileType.MP4) return@withContext null
        val key = "$accountHash/${file.name}"
        cache.get(key)?.let { return@withContext it }

        ensureActive()
        val destination = File(tempDir, "${accountHash}_${file.name}.mp4")
        val copyResult = executor.run(
            WxPaths.copyFileCommand(accountHash, file.name, destination.absolutePath),
            COPY_TIMEOUT_MS
        )
        if (copyResult.exitCode != 0) {
            AppLog.log("thumb", "copy failed exit=${copyResult.exitCode} ${file.name} ${copyResult.stderr.take(160)}")
            return@withContext null
        }

        ensureActive()
        val retriever = MediaMetadataRetriever()
        val frame = try {
            retriever.setDataSource(destination.absolutePath)
            firstFrame(retriever)?.let(::scale)
        } catch (e: Exception) {
            AppLog.log("thumb", "decode failed ${file.name}: ${e.message}")
            null
        } finally {
            retriever.release()
            destination.delete()
        }
        if (frame == null) AppLog.log("thumb", "no frame ${file.name}")
        if (frame != null) cache.put(key, frame)
        frame
    }

    fun clear() {
        cache.evictAll()
        tempDir.listFiles()?.forEach { it.delete() }
    }

    private fun firstFrame(retriever: MediaMetadataRetriever): Bitmap? =
        retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: retriever.getFrameAtTime(1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: retriever.getFrameAtTime()

    private fun scale(source: Bitmap): Bitmap {
        val targetWidth = minOf(MAX_FRAME_WIDTH, source.width)
        val targetHeight = (source.height * targetWidth.toFloat() / source.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
    }

    private companion object {
        const val MAX_CACHE_BYTES = 64 * 1024 * 1024
        const val MAX_FRAME_WIDTH = 320
        const val COPY_TIMEOUT_MS = 60_000L
    }
}
