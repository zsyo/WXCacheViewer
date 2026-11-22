package top.aico.wxcacheviewer.domain

/**
 * 导出目录下的分类子目录。
 *
 * 用户选定一个根目录后，各类文件按类型落到对应子目录，不再分散在
 * Music / Movies / Download 这些系统目录里。
 */
enum class ExportCategory(val directoryName: String) {
    VIDEO("视频"),
    IMAGE("图片"),
    VOICE("语音"),
    FILE("文件")
}

/**
 * 把系统文件选择器（SAF）返回的目录转成可写的绝对路径。
 *
 * 只认外置存储提供者的 tree documentId，形如 `primary:Download/WXCacheViewer`
 * 或 `1234-5678:导出`。应用通过 root 通道写文件，需要的是真实路径而不是 content://。
 */
object ExportLocation {

    /** 系统「文件」提供者的 authority，其它提供者（网盘等）没有本地路径。 */
    const val PROVIDER_AUTHORITY = "com.android.externalstorage.documents"

    private const val PRIMARY_VOLUME = "primary"
    private const val PRIMARY_ROOT = "/storage/emulated/0"
    private val VOLUME_PATTERN = Regex("^[A-Za-z0-9_-]{1,32}$")

    /** 解析成功返回绝对路径；整卷根目录、格式异常或含非法段时返回 null。 */
    fun resolve(documentId: String): String? {
        val separator = documentId.indexOf(':')
        if (separator <= 0 || separator == documentId.lastIndex) return null
        val volume = documentId.substring(0, separator)
        // volume 会被拼进 "/storage/$volume"，同样要防路径遍历，不能只校验 relative 段
        if (!VOLUME_PATTERN.matches(volume)) return null
        val relative = documentId.substring(separator + 1).trim('/')
        if (relative.isEmpty()) return null
        if (relative.split('/').any { it.isEmpty() || it == "." || it == ".." }) return null
        val base = if (volume == PRIMARY_VOLUME) PRIMARY_ROOT else "/storage/$volume"
        return "$base/$relative"
    }

    /** 目录在界面上的显示名，取路径最后一段。 */
    fun displayName(path: String): String =
        path.trimEnd('/').substringAfterLast('/').ifEmpty { path }
}
