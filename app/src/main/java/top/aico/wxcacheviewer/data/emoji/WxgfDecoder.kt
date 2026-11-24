package top.aico.wxcacheviewer.data.emoji

import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.os.SystemClock
import top.aico.wxcacheviewer.data.log.AppLog
import top.aico.wxcacheviewer.domain.emoji.WxgfFormat
import java.io.File
import java.nio.ByteBuffer

/**
 * 把 wxgf 里的 H.265 码流变成能看的图。
 *
 * 两条路，先试快的：
 *
 * 1. 直接把访问单元（AU）喂给 MediaCodec，从输出的 [Image] 上自己转 YUV→ARGB。
 *    不落盘、不过封装器，路径最短。
 * 2. 万一机型的 HEVC 解码器不支持 ByteBuffer 输出，退一步把 AU 封装成 MP4
 *    （容器里本来就是标准 HEVC，不需要转码），再让 [MediaMetadataRetriever] 取首帧。
 *
 * 两条路都按 AU 喂数据：wxgf 的一个分区常常装了不止一帧，整段当一帧交上去，
 * 解码器会当非法数据丢掉，真机上的表现就是首帧怎么都取不到。
 *
 * 曾经用过「[android.media.ImageReader] 接解码器 Surface」的方案，方向是错的：
 * wxgf 头里写的宽高并不等于码流真实尺寸，ImageReader 按错尺寸创建后
 * `getPlanes()` 会在 JNI 里直接 abort（`non-zero capacity for nullptr pointer`），
 * 应用闪退、Kotlin 侧拦不住。
 */
object WxgfDecoder {

    private const val MIME = "video/hevc"

    private const val CSD_KEY = "csd-0"

    /** 容器里没写帧率，取一个常见值；只影响动图节奏与时间戳，不影响首帧。 */
    private const val FRAME_INTERVAL_US = 33_000L

    /** 单次出队等待：够一次解码，又不至于卡住主流程。 */
    private const val DEQUEUE_TIMEOUT_US = 5_000L

    /** 一次尝试总时长上限，超时按失败处理，别让卡片一直转圈。 */
    private const val ATTEMPT_TIMEOUT_MS = 900L

    /** 解码器输入缓冲开大一点，省得大帧被截断。 */
    private const val MAX_INPUT_BYTES = 1 shl 20

    private const val RETRY_DELAY_MS = 60L

    /**
     * 解码串行化的锁。
     *
     * 一台设备能同时开的 HEVC 解码实例很少：三个实例并发跑时，真机上出现过成片
     * 解不出帧（同一份码流单跑就没问题），所以这里串行。
     */
    private val decodeLock = Any()

    /**
     * 解出首帧。封装用的中间 MP4 写在 [workDir]，用完即删（只在兜底路径上产生）。
     *
     * 失败（码流异常、机型不支持 HEVC、磁盘写不了）返回 null，卡片显示占位。
     */
    fun decodeFirstFrame(container: WxgfFormat.Container, workDir: File): Bitmap? =
        synchronized(decodeLock) {
            attempt(container, workDir) ?: run {
                // 多半是上一个解码实例还没完全释放，等一下再试一次
                Thread.sleep(RETRY_DELAY_MS)
                attempt(container, workDir)
            }
        }

    private fun attempt(container: WxgfFormat.Container, workDir: File): Bitmap? =
        fromBytes(container) ?: muxAndRetrieve(container, workDir)

    /** 主路径：MediaCodec 直接解码，取第一张能拿到画面的输出。 */
    private fun fromBytes(container: WxgfFormat.Container): Bitmap? {
        var codec: MediaCodec? = null
        return try {
            codec = MediaCodec.createDecoderByType(MIME)
            val format = MediaFormat.createVideoFormat(MIME, container.width, container.height).apply {
                setByteBuffer(CSD_KEY, ByteBuffer.wrap(container.parameterSets))
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_BYTES)
            }
            codec.configure(format, null, null, 0)
            codec.start()
            drain(codec, container)
        } catch (e: Exception) {
            AppLog.log("emoji", "wxgf codec failed: ${e.message}")
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
        }
    }

    private fun drain(codec: MediaCodec, container: WxgfFormat.Container): Bitmap? {
        val info = MediaCodec.BufferInfo()
        val deadline = SystemClock.elapsedRealtime() + ATTEMPT_TIMEOUT_MS
        var next = 0
        var frame = 0L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (next < container.units.size) {
                val input = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                if (input >= 0) {
                    val buffer = codec.getInputBuffer(input)
                    val unit = container.units[next++]
                    if (buffer == null || buffer.capacity() < unit.size) {
                        AppLog.log("emoji", "wxgf input too small ${buffer?.capacity()} < ${unit.size}")
                        return null
                    }
                    buffer.clear()
                    buffer.put(unit)
                    codec.queueInputBuffer(input, 0, unit.size, frame * FRAME_INTERVAL_US, 0)
                    frame++
                }
            }
            val output = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
            when {
                output >= 0 -> {
                    val bitmap = runCatching {
                        codec.getOutputImage(output)?.let(::toBitmap)
                    }.getOrNull()
                    codec.releaseOutputBuffer(output, false)
                    if (bitmap != null) {
                        AppLog.log(
                            "emoji",
                            "wxgf decoded ${bitmap.width}x${bitmap.height} " +
                                "codec=${codec.name} units=${container.units.size}"
                        )
                        return bitmap
                    }
                }

                output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                    AppLog.log("emoji", "wxgf output format ${codec.outputFormat}")

                info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 -> break
            }
        }
        AppLog.log("emoji", "wxgf no frame codec=${codec.name} units=${container.units.size}")
        return null
    }

    /** 兜底路径：封装成 MP4 再让系统取首帧，解码器不认识 ByteBuffer 输出时用。 */
    private fun muxAndRetrieve(container: WxgfFormat.Container, workDir: File): Bitmap? {
        val temp = File(workDir, "wxgf-${System.nanoTime()}.mp4")
        return try {
            if (mux(container, temp)) firstFrame(temp) else null
        } catch (e: Exception) {
            AppLog.log("emoji", "wxgf retrieve failed: ${e.message}")
            null
        } finally {
            temp.delete()
        }
    }

    /**
     * 把访问单元写成 MP4：一个 AU 一个样本，样本内是「4 字节长度 + NAL」，
     * 参数集只放在 csd-0 里（对应 hvc1 的语义）。返回是否成功。
     */
    private fun mux(container: WxgfFormat.Container, target: File): Boolean {
        val format = MediaFormat.createVideoFormat(MIME, container.width, container.height).apply {
            setByteBuffer(CSD_KEY, ByteBuffer.wrap(container.parameterSets))
        }
        var muxer: MediaMuxer? = null
        var started = false
        return try {
            muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val track = muxer.addTrack(format)
            muxer.start()
            started = true
            val info = MediaCodec.BufferInfo()
            var presentationTimeUs = 0L
            container.units.forEach { unit ->
                val sample = WxgfFormat.toSampleData(unit)
                if (sample.isEmpty()) return@forEach
                info.set(0, sample.size, presentationTimeUs, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                muxer.writeSampleData(track, ByteBuffer.wrap(sample), info)
                presentationTimeUs += FRAME_INTERVAL_US
            }
            true
        } catch (e: Exception) {
            AppLog.log("emoji", "wxgf mux failed: ${e.message}")
            false
        } finally {
            if (started) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    private fun firstFrame(mp4: File): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(mp4.absolutePath)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val frame = retriever.getFrameAtTime(0) ?: retriever.frameAtTime
            AppLog.log(
                "emoji",
                "wxgf mp4 frame=${frame?.width}x${frame?.height} track=${width}x$height"
            )
            frame
        } catch (e: Exception) {
            AppLog.log("emoji", "wxgf mp4 failed: ${e.message}")
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * YUV_420_888 → ARGB_8888。
     *
     * 输出的行宽常常带对齐填充，必须按 [Image.Plane] 的 rowStride/pixelStride 取值，
     * 不能按「宽 × 高」假定连续存放；可见区域还要用 cropRect 裁。
     */
    private fun toBitmap(image: Image): Bitmap? {
        if (image.planes.size < 3) return null
        val crop = image.cropRect
        val width = crop.width()
        val height = crop.height()
        if (width <= 0 || height <= 0) return null
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = yPlane.buffer.apply { rewind() }
        val uBuffer = uPlane.buffer.apply { rewind() }
        val vBuffer = vPlane.buffer.apply { rewind() }
        val pixels = IntArray(width * height)
        for (row in 0 until height) {
            val yRow = (crop.top + row) * yPlane.rowStride
            val uvRow = (crop.top + row) / 2
            for (col in 0 until width) {
                val y = yBuffer.get(yRow + (crop.left + col) * yPlane.pixelStride).toInt() and 0xFF
                val uvCol = (crop.left + col) / 2
                val uIndex = uvRow * uPlane.rowStride + uvCol * uPlane.pixelStride
                val vIndex = uvRow * vPlane.rowStride + uvCol * vPlane.pixelStride
                val u = (uBuffer.get(uIndex).toInt() and 0xFF) - 128
                val v = (vBuffer.get(vIndex).toInt() and 0xFF) - 128
                // 有限范围 BT.601 定点转 RGB
                val c = (y - 16).coerceAtLeast(0)
                val r = (298 * c + 409 * v + 128) shr 8
                val g = (298 * c - 100 * u - 208 * v + 128) shr 8
                val b = (298 * c + 516 * u + 128) shr 8
                pixels[row * width + col] =
                    (0xFF shl 24) or (clamp(r) shl 16) or (clamp(g) shl 8) or clamp(b)
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun clamp(value: Int): Int = when {
        value < 0 -> 0
        value > 255 -> 255
        else -> value
    }
}
