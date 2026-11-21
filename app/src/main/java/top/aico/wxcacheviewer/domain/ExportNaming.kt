package top.aico.wxcacheviewer.domain

import top.aico.wxcacheviewer.domain.model.CacheFile
import top.aico.wxcacheviewer.domain.source.SourceRegistry
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 导出文件的命名与重名处理。
 *
 * 缓存本体多数没有可读文件名（md5、时间戳），因此统一重命名成
 * `<源前缀>_年月日_时分秒_<相对路径哈希 8 位>.<扩展名>`；末段用相对路径哈希，
 * 保证不同子目录下的同名文件不会互相覆盖。聊天文件本身就有可读文件名，直接沿用。
 */
object ExportNaming {

    fun nameFor(
        file: CacheFile,
        extension: String,
        nowMillis: Long = System.currentTimeMillis()
    ): String {
        val source = SourceRegistry.byId(file.sourceId)
        if (source.preserveFileName) {
            val cleaned = file.name.replace('/', '_').replace('\\', '_').trim()
            if (cleaned.isNotEmpty() && cleaned != "." && cleaned != "..") return cleaned
        }
        val timeMillis = if (file.modifiedAt > 0L) file.modifiedAt * 1000 else nowMillis
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(timeMillis))
        return "${source.exportTag}_${stamp}_${shortDigest(file.relativePath)}.$extension"
    }

    /** 目标目录已有同名文件时依次尝试 `名字(1).ext`、`名字(2).ext`，不覆盖用户已有文件。 */
    fun uniqueDestination(directory: File, name: String): File {
        val first = File(directory, name)
        if (!first.exists()) return first
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val suffix = if (dot > 0) name.substring(dot) else ""
        var index = 1
        while (true) {
            val candidate = File(directory, "$base($index)$suffix")
            if (!candidate.exists()) return candidate
            index++
        }
    }

    private fun shortDigest(value: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(8)
}
