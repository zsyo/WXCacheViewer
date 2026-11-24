package top.aico.wxcacheviewer.ui.common

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import top.aico.wxcacheviewer.domain.model.CacheFile
import top.aico.wxcacheviewer.domain.model.mime
import java.io.File

private const val AUTHORITY_SUFFIX = ".fileprovider"

/**
 * 打开文件时优先用系统 MimeTypeMap 按扩展名判定：缓存类型是我们自己推断的，
 * 而系统认识更全（例如 rar、7z、heic）。判不出来才回退到缓存类型。
 */
fun mimeOf(file: CacheFile): String {
    val dot = file.name.lastIndexOf('.')
    if (dot in 1 until file.name.lastIndex) {
        val extension = file.name.substring(dot + 1).lowercase()
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)?.let { return it }
    }
    return file.type.mime
}

/**
 * 用系统浏览器打开链接。应用本身不联网，跳转交给浏览器处理。
 * 没有能处理 ACTION_VIEW 的应用时返回 false，由调用方决定怎么提示。
 */
fun openUrl(context: Context, url: String): Boolean = runCatching {
    val intent = Intent(Intent.ACTION_VIEW, url.toUri())
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}.isSuccess

/**
 * 用系统的「打开方式」打开应用缓存里复制出来的文件。
 *
 * 文件在微信私有目录，必须先由 MediaPreparer 复制到 cache/media，再经 FileProvider
 * 以 content:// 分享；直接传 file:// 在 Android 7 以上会抛 FileUriExposedException。
 */
fun openFileWith(context: Context, target: File, cacheFile: CacheFile): Boolean {
    val uri = FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, target)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeOf(cacheFile))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
}
