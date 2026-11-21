package top.aico.wxcacheviewer.data.image

import android.content.Context
import android.os.Build
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import java.io.File

/**
 * 两个 Coil 实例：
 * - [static] 不注册动图解码器，网格里只解首帧，避免一屏多个大 GIF 同时解码；
 * - [animated] 注册动图解码器，仅用于全屏预览页。
 */
object CoilImageLoaders {

    lateinit var static: ImageLoader
        private set

    lateinit var animated: ImageLoader
        private set

    fun init(context: Context) {
        if (this::static.isInitialized) return
        static = build(context, animated = false)
        animated = build(context, animated = true)
    }

    private fun build(context: Context, animated: Boolean): ImageLoader =
        ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizePercent(0.2)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(context.cacheDir, DISK_CACHE_DIR).apply { mkdirs() })
                    .maxSizeBytes(DISK_CACHE_BYTES)
                    .build()
            }
            .components {
                if (animated) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        add(ImageDecoderDecoder.Factory())
                    } else {
                        add(GifDecoder.Factory())
                    }
                }
            }
            .crossfade(false)
            .build()

    private const val DISK_CACHE_DIR = "coil_image"
    private const val DISK_CACHE_BYTES = 256L * 1024 * 1024
}
