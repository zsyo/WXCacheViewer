package top.aico.wxcacheviewer.data.image

import android.content.Context
import android.os.Build
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.memory.MemoryCache
import coil3.request.crossfade
import coil3.serviceLoaderEnabled
import okio.Path.Companion.toOkioPath
import java.io.File

/**
 * 两个 Coil 实例：
 * - [static] 不注册动图解码器，网格里只解首帧，避免一屏多个大 GIF 同时解码；
 * - [animated] 注册动图解码器，仅用于全屏预览页。
 *
 * Coil 3 默认经 ServiceLoader 注册依赖里的解码器（含 coil-gif），因此两个实例
 * 都显式关闭自动注册，动图解码器只在 [animated] 手动加入，保持与 Coil 2 一致的
 * 「网格只解首帧」行为。基础静态图解码与本地 File 加载由 coil-core 默认提供。
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
            .serviceLoaderEnabled(false)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.2)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(context.cacheDir, DISK_CACHE_DIR).toOkioPath())
                    .maxSizeBytes(DISK_CACHE_BYTES)
                    .build()
            }
            .components {
                if (animated) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        add(AnimatedImageDecoder.Factory())
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
