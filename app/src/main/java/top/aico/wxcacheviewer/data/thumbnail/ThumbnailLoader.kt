package top.aico.wxcacheviewer.data.thumbnail

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import top.aico.wxcacheviewer.data.log.AppLog
import top.aico.wxcacheviewer.data.root.RootCommandExecutor
import top.aico.wxcacheviewer.data.root.WxPaths
import top.aico.wxcacheviewer.domain.model.CacheFile
import top.aico.wxcacheviewer.domain.model.FileType
import top.aico.wxcacheviewer.domain.source.SourceRegistry
import java.io.File

class ThumbnailLoader(
    context: Context,
    private val executor: RootCommandExecutor
) {
    private val cache = LruCache<String, Bitmap>(MAX_CACHE_BYTES)
    private val tempDir = File(context.cacheDir, "thumb_tmp").apply { mkdirs() }

    /**
     * 取视频缩略图：聊天视频优先用同目录下的同名 jpg 首帧（省去解码整个视频），
     * 其它情况复制视频后解码首帧。
     */
    suspend fun load(file: CacheFile): Bitmap? = withContext(Dispatchers.IO) {
        cache.get(file.key)?.let { return@withContext it }

        ensureActive()
        val frame = when {
            file.type != FileType.MP4 -> null
            file.hasThumbnail -> loadJpegThumbnail(file) ?: loadFirstFrame(file)
            else -> loadFirstFrame(file)
        }
        if (frame == null) AppLog.log("thumb", "no frame ${file.relativePath}")
        if (frame != null) cache.put(file.key, frame)
        frame
    }

    private suspend fun loadJpegThumbnail(file: CacheFile): Bitmap? {
        val destination = File(tempDir, "${tempName(file)}.jpg")
        val result = executor.run(
            WxPaths.copyChatVideoThumbnailCommand(
                file.accountHash,
                file.relativePath,
                destination.absolutePath
            ),
            COPY_TIMEOUT_MS
        )
        if (result.exitCode != 0 || !result.stdout.contains(OK_MARK)) {
            destination.delete()
            return null
        }
        val bitmap = decodeSampled(destination)?.let(::scale)
        destination.delete()
        return bitmap
    }

    private suspend fun loadFirstFrame(file: CacheFile): Bitmap? {
        val destination = File(tempDir, "${tempName(file)}.mp4")
        val source = SourceRegistry.byId(file.sourceId)
        val copyResult = executor.run(
            WxPaths.copySourceFileCommand(
                file.accountHash,
                source,
                file.relativePath,
                destination.absolutePath
            ),
            COPY_TIMEOUT_MS
        )
        if (copyResult.exitCode != 0) {
            AppLog.log(
                "thumb",
                "copy failed exit=${copyResult.exitCode} ${file.relativePath} ${copyResult.stderr.take(160)}"
            )
            return null
        }

        currentCoroutineContext().ensureActive()
        val retriever = MediaMetadataRetriever()
        val frame = try {
            retriever.setDataSource(destination.absolutePath)
            firstFrame(retriever)?.let(::scale)
        } catch (e: Exception) {
            AppLog.log("thumb", "decode failed ${file.relativePath}: ${e.message}")
            null
        } finally {
            retriever.release()
            destination.delete()
        }
        return frame
    }

    fun clear() {
        cache.evictAll()
        tempDir.listFiles()?.forEach { it.delete() }
    }

    private fun firstFrame(retriever: MediaMetadataRetriever): Bitmap? =
        retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: retriever.getFrameAtTime(1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: retriever.getFrameAtTime()

    private fun decodeSampled(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_FRAME_WIDTH) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    /** 临时文件名要跨源唯一，递归源里不同目录可能同名。 */
    private fun tempName(file: CacheFile): String =
        "${file.sourceId}_${file.relativePath.replace('/', '_')}"

    private fun scale(source: Bitmap): Bitmap {
        val targetWidth = minOf(MAX_FRAME_WIDTH, source.width)
        val targetHeight = (source.height * targetWidth.toFloat() / source.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
    }

    private companion object {
        const val MAX_CACHE_BYTES = 64 * 1024 * 1024
        const val MAX_FRAME_WIDTH = 320
        const val COPY_TIMEOUT_MS = 60_000L
        const val OK_MARK = "OK"
    }
}
