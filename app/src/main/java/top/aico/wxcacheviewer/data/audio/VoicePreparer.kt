package top.aico.wxcacheviewer.data.audio

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.aico.wxcacheviewer.data.log.AppLog
import top.aico.wxcacheviewer.data.root.RootCommandExecutor
import top.aico.wxcacheviewer.data.root.WxPaths
import top.aico.wxcacheviewer.domain.ExportNaming
import top.aico.wxcacheviewer.domain.VoiceFormat
import top.aico.wxcacheviewer.domain.model.CacheFile
import top.aico.wxcacheviewer.domain.source.SourceRegistry
import java.io.File
import java.security.MessageDigest

/**
 * 微信语音的取用与转码。
 *
 * `voice2` 下的 `.amr` 是 SILK v3，且微信在标准头前多写了一个 `0x02`，直接解会失败。
 * 流程是：root 复制原始字节 → 剥掉私有头写成标准 SILK → 交给解码器转 wav（播放）
 * 或 mp3（导出）。解码是 CPU 密集的原生调用，用 [decodeLock] 串行化，避免多线程同时进解码器。
 */
class VoicePreparer(
    context: Context,
    private val executor: RootCommandExecutor
) {
    private val rawDir = File(context.cacheDir, RAW_DIR)
    private val decodedDir = File(context.cacheDir, DECODED_DIR)
    private val decodeLock = Mutex()

    /** 返回可直接播放的文件：SILK 解码后的 wav，真 AMR 则原样返回。 */
    suspend fun prepare(file: CacheFile): File = withContext(Dispatchers.IO) {
        val raw = copyRaw(file)
        val silk = normalizeSilk(file, raw) ?: return@withContext raw
        decodeWav(file, silk)
    }

    /**
     * 导出到公共目录：SILK 转 mp3（外部播放器基本都认），真 AMR 原样导出。
     * 应用自己写不了公共目录，落盘仍然借 root。
     */
    suspend fun export(file: CacheFile, destinationDir: File): File = withContext(Dispatchers.IO) {
        val raw = copyRaw(file)
        val silk = normalizeSilk(file, raw)
        val local: File
        val extension: String
        if (silk == null) {
            local = raw
            extension = "amr"
        } else {
            local = decodeMp3(file, silk)
            extension = "mp3"
        }
        val destination = ExportNaming.uniqueDestination(
            destinationDir,
            ExportNaming.nameFor(file, extension)
        )
        val result = executor.run(
            WxPaths.exportLocalFileCommand(local.absolutePath, destination.absolutePath)
        )
        if (result.exitCode != 0 || !result.stdout.contains(OK_MARK)) {
            throw IllegalStateException(result.stderr.ifBlank { "导出失败" })
        }
        destination
    }

    /** root 把原始语音复制到应用私有目录；文件名带文件大小，内容变化后自然失效。 */
    private suspend fun copyRaw(file: CacheFile): File {
        rawDir.mkdirs()
        val target = File(rawDir, "${cacheId(file)}.amr")
        if (target.isFile && target.length() == file.size) {
            target.setLastModified(System.currentTimeMillis())
            return target
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
        if (result.exitCode != 0 || !target.isFile) {
            AppLog.log(
                "voice",
                "copy failed exit=${result.exitCode} ${file.relativePath} ${result.stderr.take(160)}"
            )
            target.delete()
            throw IllegalStateException(
                result.stderr.ifBlank { "复制语音文件失败，请检查 root 授权" }
            )
        }
        return target
    }

    /** 标准化的 SILK 文件，附带按帧数算出的期望时长，用于校验解码结果的采样率。 */
    private data class SilkInput(val file: File, val expectedDurationMs: Long)

    /**
     * 剥离微信私有头，返回标准 SILK；真是 AMR 时返回 null（可直接播放）。
     * 格式无法识别时抛出，交给界面提示，而不是静默播放出噪声。
     */
    private fun normalizeSilk(file: CacheFile, raw: File): SilkInput? {
        val bytes = raw.readBytes()
        val kind = VoiceFormat.kindOf(bytes)
        if (kind == VoiceFormat.Kind.AMR) return null
        val silk = File(rawDir, "${cacheId(file)}.silk")
        if (kind == VoiceFormat.Kind.SILK && silk.isFile && silk.length() == bytes.size.toLong()) {
            return SilkInput(silk, VoiceFormat.durationMillisOfStandardSilk(bytes))
        }
        val standard = try {
            VoiceFormat.toStandardSilk(bytes)
        } catch (e: IllegalArgumentException) {
            AppLog.log("voice", "unknown format ${file.relativePath} head=${bytes.take(4)}")
            throw IllegalStateException("无法识别的语音格式")
        }
        silk.writeBytes(standard)
        return SilkInput(silk, VoiceFormat.durationMillisOfStandardSilk(standard))
    }

    /**
     * 解码成 wav。
     *
     * 微信早期版本录的是 16kHz、新版是 24kHz，而 SILK 头里没有记录采样率。
     * 先按 24kHz 解，用「帧数 × 20ms」的期望时长校验一次：偏差过大说明解码器
     * 没有把内部采样率重采样过来，再按 16kHz 解一遍覆盖。
     * 最终采用的采样率记在 `.rate` 旁注文件里，下次命中缓存就不必重复校验。
     */
    private suspend fun decodeWav(file: CacheFile, silk: SilkInput): File {
        decodedDir.mkdirs()
        val wav = File(decodedDir, "${cacheId(file)}.wav")
        val rateNote = File(decodedDir, "${cacheId(file)}.rate")
        val expected = silk.expectedDurationMs

        if (wav.isFile && wav.length() > HEADER_BYTES) {
            val cachedRate = rateNote.readRate() ?: SAMPLE_RATE
            if (expected <= 0L || durationMatches(wav, cachedRate, expected)) {
                wav.setLastModified(System.currentTimeMillis())
                return wav
            }
            wav.delete()
            rateNote.delete()
        }

        var rate = SAMPLE_RATE
        var decoded = runDecode(file, silk.file, wav, rate, "wav")
        if (expected > 0L && !durationMatches(decoded, rate, expected)) {
            AppLog.log(
                "voice",
                "rate mismatch ${file.relativePath} expected=${expected}ms " +
                    "actual=${pcmDurationMs(decoded, rate)}ms"
            )
            decoded.delete()
            rate = FALLBACK_SAMPLE_RATE
            decoded = runDecode(file, silk.file, wav, rate, "wav")
        }
        rateNote.writeText(rate.toString())
        return decoded
    }

    private suspend fun decodeMp3(file: CacheFile, silk: SilkInput): File = decode(
        file = file,
        input = silk.file,
        extension = "mp3",
        rate = File(decodedDir, "${cacheId(file)}.rate").readRate() ?: SAMPLE_RATE
    ) { input, output, rate ->
        SilkCodec.decodeToMp3(input.absolutePath, output.absolutePath, rate, MP3_BITRATE) == 0
    }

    /** 解码到私有缓存目录；同一文件的产物会被复用，重复播放不必重新解码。 */
    private suspend fun decode(
        file: CacheFile,
        input: File,
        extension: String,
        rate: Int = SAMPLE_RATE,
        convert: (File, File, Int) -> Boolean
    ): File {
        decodedDir.mkdirs()
        val output = File(decodedDir, "${cacheId(file)}.$extension")
        if (output.isFile && output.length() > HEADER_BYTES) {
            output.setLastModified(System.currentTimeMillis())
            return output
        }
        return runDecode(file, input, output, rate, extension) { source, target ->
            convert(source, target, rate)
        }
    }

    /**
     * 真正调用原生解码器。解码器不保证线程安全，用 [decodeLock] 串行；
     * 失败可能以 UnsatisfiedLinkError 的形式出现，一并兜住。
     */
    private suspend fun runDecode(
        file: CacheFile,
        input: File,
        output: File,
        rate: Int,
        extension: String,
        convert: (File, File) -> Boolean = { source, target ->
            SilkCodec.decodeToWav(source.absolutePath, target.absolutePath, rate) == 0
        }
    ): File = decodeLock.withLock {
        output.delete()
        val started = System.currentTimeMillis()
        try {
            convert(input, output)
        } catch (e: Throwable) {
            output.delete()
            AppLog.log("voice", "decode $extension failed ${file.relativePath}: ${e.message}")
            throw IllegalStateException("语音解码失败", e)
        }
        // 库的返回值在成功/失败上并不可靠，直接看产物是否可用
        if (!output.isFile || output.length() <= HEADER_BYTES) {
            output.delete()
            AppLog.log("voice", "decode $extension empty ${file.relativePath}")
            throw IllegalStateException("语音解码失败")
        }
        AppLog.log(
            "voice",
            "decoded $extension ${file.relativePath} rate=$rate " +
                "${System.currentTimeMillis() - started}ms ${output.length()}B"
        )
        prune()
        output
    }

    /** 16bit 单声道 wav 的时长（毫秒）。 */
    private fun pcmDurationMs(wav: File, rate: Int): Long {
        val dataBytes = (wav.length() - HEADER_BYTES).coerceAtLeast(0L)
        return dataBytes * 1000 / (2L * rate)
    }

    private fun durationMatches(wav: File, rate: Int, expectedMs: Long): Boolean {
        val actual = pcmDurationMs(wav, rate)
        val tolerance = (expectedMs * DURATION_TOLERANCE).toLong()
        return actual > 0L && Math.abs(actual - expectedMs) <= tolerance
    }

    private fun File.readRate(): Int? =
        if (isFile) readText().trim().toIntOrNull() else null

    private fun cacheId(file: CacheFile): String =
        MessageDigest.getInstance("SHA-1")
            .digest("${file.key}#${file.size}".toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)

    /** 只保留最近使用的若干个中间文件，避免私有目录无限增长。 */
    private fun prune() {
        listOf(rawDir, decodedDir).forEach { dir ->
            val files = dir.listFiles()?.filter { it.isFile } ?: return@forEach
            files.sortedByDescending { it.lastModified() }
                .drop(MAX_CACHED_FILES)
                .forEach { it.delete() }
        }
    }

    private companion object {
        const val RAW_DIR = "voice_raw"
        const val DECODED_DIR = "voice_decoded"

        /** 微信新版录音是 24kHz；解码器会把内部采样率重采样到这个输出率。 */
        const val SAMPLE_RATE = 24_000

        /** 老版本微信录的是 16kHz。24kHz 解出来的时长对不上时改用它。 */
        const val FALLBACK_SAMPLE_RATE = 16_000

        /** 解码时长与「帧数 × 20ms」允许的相对偏差。 */
        const val DURATION_TOLERANCE = 0.15

        /** 语音导出用 cd 级码率明显过剩，64k 单声道听感已足够。 */
        const val MP3_BITRATE = 64

        const val COPY_TIMEOUT_MS = 60_000L
        const val MAX_CACHED_FILES = 40
        const val HEADER_BYTES = 44L
        const val OK_MARK = "OK"
    }
}
