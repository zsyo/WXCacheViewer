package top.aico.wxcacheviewer.domain.source

import top.aico.wxcacheviewer.domain.model.FileType

/**
 * 全部缓存源的定义。新增一类缓存只需要在这里加一条，并在 [SourceTab] 里挂到某个标签页。
 */
object SourceRegistry {

    private val FINDER_VIDEO_NAME = Regex("^finder_video(?:_xWT[0-9]+)?__?[0-9a-f]{32}$")
    private val CHAT_VIDEO_NAME = Regex("^[0-9]{12,20}\\.mp4$")
    private val EMOJI_NAME = Regex("^[0-9a-f]{32}_(?:gif|cover|thumb)$")
    private val CHAT_IMAGE_PATH = Regex("^(?:[0-9a-f]{2}/){1,3}th_[0-9a-f]{32}$")
    private val SNS_PATH = Regex("^(?:[0-9a-f]{1,2}/){1,3}(?:snst|snsu)_[0-9]+$")

    /** 视频号视频：文件名无扩展名，靠文件头判断类型。 */
    val FINDER_VIDEO = CacheSource(
        id = "finder_video",
        title = "视频号",
        root = SourceRoot.CACHE,
        relativeDir = "finder/video",
        relativePathRegex = FINDER_VIDEO_NAME,
        exportTag = "wxvideo",
        exportKind = ExportKind.MOVIES,
        globs = listOf("*"),
        probeHeader = true
    )

    /** 聊天里收发的视频：标准 MP4，同目录下有同名 jpg 首帧。 */
    val CHAT_VIDEO = CacheSource(
        id = "chat_video",
        title = "聊天视频",
        root = SourceRoot.MICROMSG,
        relativeDir = "video",
        relativePathRegex = CHAT_VIDEO_NAME,
        exportTag = "wxchatvideo",
        exportKind = ExportKind.MOVIES,
        globs = listOf("*.mp4"),
        hasJpegThumbnail = true,
        typeHint = { if (it.endsWith(".mp4")) FileType.MP4 else null }
    )

    /**
     * 表情：只收 `_gif`（动图）、`_cover`、`_thumb`（表情包封面）。
     * 裸 32 位 md5 的文件是加密数据，无法解码，因此不在白名单内。
     */
    val EMOJI = CacheSource(
        id = "emoji",
        title = "表情",
        root = SourceRoot.MICROMSG,
        relativeDir = "emoji",
        relativePathRegex = EMOJI_NAME,
        exportTag = "wxemoji",
        exportKind = ExportKind.PICTURES,
        globs = listOf("*_gif", "*_cover", "*_thumb"),
        probeHeader = true
    )

    /** 聊天图片缩略图：两级十六进制子目录下的 `th_` 前缀 JPEG。 */
    val CHAT_IMAGE = CacheSource(
        id = "chat_image",
        title = "聊天图片",
        root = SourceRoot.MICROMSG,
        relativeDir = "image2",
        relativePathRegex = CHAT_IMAGE_PATH,
        exportTag = "wximage",
        exportKind = ExportKind.PICTURES,
        recursive = true,
        maxDepth = 4,
        findNames = listOf("th_*"),
        typeHint = { FileType.JPEG },
        allowDelete = false,
        listTimeoutMs = RECURSIVE_LIST_TIMEOUT_MS
    )

    /** 朋友圈缓存：`snst_` 为 PNG 缩略图，`snsu_` 为 JPEG。 */
    val SNS = CacheSource(
        id = "sns",
        title = "朋友圈",
        root = SourceRoot.CACHE,
        relativeDir = "sns",
        relativePathRegex = SNS_PATH,
        exportTag = "wxsns",
        exportKind = ExportKind.PICTURES,
        recursive = true,
        maxDepth = 4,
        findNames = listOf("snst_*", "snsu_*"),
        typeHint = { rel ->
            when {
                rel.contains("snst_") -> FileType.PNG
                rel.contains("snsu_") -> FileType.JPEG
                else -> null
            }
        },
        allowDelete = false,
        listTimeoutMs = RECURSIVE_LIST_TIMEOUT_MS
    )

    val all: List<CacheSource> = listOf(FINDER_VIDEO, CHAT_VIDEO, EMOJI, CHAT_IMAGE, SNS)

    fun byId(id: String): CacheSource = all.firstOrNull { it.id == id } ?: FINDER_VIDEO

    /** 递归源最多允许的目录层数（相对路径段数，含文件名）。 */
    private const val MAX_PATH_LENGTH = 512

    /**
     * 相对路径白名单：先做通用的段级检查，再匹配该源的正则。
     *
     * 段级检查是第二道闸，即使某个源的正则写宽了，也不会放过 `..`、绝对路径或超深路径。
     */
    fun isValidRelativePath(source: CacheSource, relativePath: String): Boolean {
        if (relativePath.isEmpty() || relativePath.length > MAX_PATH_LENGTH) return false
        if (relativePath.startsWith("/") || relativePath.endsWith("/")) return false
        if (relativePath.contains('\\') || relativePath.contains('\u0000')) return false
        val segments = relativePath.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return false
        if (segments.size > source.maxDepth) return false
        return source.relativePathRegex.matches(relativePath)
    }
}
