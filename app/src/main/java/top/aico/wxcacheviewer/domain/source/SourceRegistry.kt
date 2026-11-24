package top.aico.wxcacheviewer.domain.source

import top.aico.wxcacheviewer.domain.ChatFileTypes
import top.aico.wxcacheviewer.domain.model.FileType

/**
 * 全部缓存源的定义。新增一类缓存只需要在这里加一条，并在 [SourceTab] 里挂到某个标签页。
 */
object SourceRegistry {

    private val FINDER_VIDEO_NAME = Regex("^finder_video(?:_xWT[0-9]+)?__?[0-9a-f]{32}$")
    private val CHAT_VIDEO_NAME = Regex("^[0-9]{12,20}\\.mp4$")
    private val EMOJI_NAME = Regex("^[0-9a-f]{32}(?:_(?:gif|cover|thumb))?$")

    /** 裸 32 位 md5：表情目录里头部被 AES 加密的那一类文件。 */
    private val EMOJI_ENCRYPTED_NAME = Regex("^[0-9a-f]{32}$")
    private val CHAT_IMAGE_PATH = Regex("^(?:[0-9a-f]{2}/){1,3}th_[0-9a-f]{32}$")
    private val SNS_PATH = Regex("^(?:[0-9a-f]{1,2}/){1,3}(?:snst|snsu)_[0-9]+$")

    /** 聊天文件名：中文、空格、括号都合法，只排除路径分隔符与控制字符。 */
    private val CHAT_FILE_NAME = Regex("^[^/\\\\\\u0000-\\u001F]{1,192}$")

    /** 语音：`voice2/<两位 hex>/<两位 hex>/msg_amr_<hex>.amr`，个别版本只有一级子目录。 */
    private val VOICE_PATH = Regex("^(?:[0-9a-f]{2}/){1,2}msg_amr_[0-9a-f]{8,40}\\.amr$")

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
     * 表情：`_gif`（动图）、`_cover`、`_thumb`（封面）三种是明文；
     * 裸 32 位 md5 是「只有前 1024 字节被 AES 加密」的文件，解密后与 `_gif` 完全一致，
     * 因此同时存在 `_gif` 时跳过裸文件，避免同一张表情出现两遍。
     *
     * 裸文件解密后可能是 GIF/PNG/JPEG，也可能是微信私有的 wxgf（HEVC，暂不解码）。
     */
    val EMOJI = CacheSource(
        id = "emoji",
        title = "表情",
        root = SourceRoot.MICROMSG,
        relativeDir = "emoji",
        relativePathRegex = EMOJI_NAME,
        exportTag = "wxemoji",
        exportKind = ExportKind.PICTURES,
        globs = listOf("*"),
        probeHeader = true,
        encryptedName = EMOJI_ENCRYPTED_NAME,
        skipSiblingSuffix = "_gif"
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

    /**
     * 语音：`MicroMsg/<hash>/voice2` 下按两级十六进制目录散列，文件后缀 `.amr` 但内容是
     * SILK v3（见 `VoiceFormat`），需要先解码成 PCM 才能播放，因此导出统一转成 mp3。
     *
     * 目录结构与 `image2` 同量级，同样按一级子目录分页扫描；语音属于聊天记录的一部分，
     * 不提供删除。
     */
    val VOICE = CacheSource(
        id = "voice",
        title = "语音",
        root = SourceRoot.MICROMSG,
        relativeDir = "voice2",
        relativePathRegex = VOICE_PATH,
        exportTag = "wxvoice",
        exportKind = ExportKind.MUSIC,
        recursive = true,
        maxDepth = 3,
        findNames = listOf("*.amr"),
        typeHint = { FileType.SILK },
        allowDelete = false,
        listTimeoutMs = RECURSIVE_LIST_TIMEOUT_MS
    )

    /**
     * 聊天文件：聊天里收发的文档、压缩包、安装包等，落在 `MicroMsg/<hash>/attachment`。
     * 文件名保留原始扩展名，因此按扩展名推断类型；这类文件是聊天记录的一部分，不提供删除。
     */
    val CHAT_FILE = CacheSource(
        id = "chat_file",
        title = "文件",
        root = SourceRoot.MICROMSG,
        relativeDir = "attachment",
        relativePathRegex = CHAT_FILE_NAME,
        exportTag = "wxfile",
        exportKind = ExportKind.DOWNLOADS,
        globs = listOf("*"),
        typeHint = { ChatFileTypes.fromName(it) },
        allowDelete = false,
        pageSize = 40,
        preserveFileName = true
    )

    val all: List<CacheSource> =
        listOf(FINDER_VIDEO, CHAT_VIDEO, EMOJI, CHAT_IMAGE, SNS, VOICE, CHAT_FILE)

    fun byId(id: String): CacheSource = all.firstOrNull { it.id == id } ?: FINDER_VIDEO

    /** 递归源最多允许的目录层数（相对路径段数，含文件名）。 */
    private const val MAX_PATH_LENGTH = 512

    /** 单段目录名允许的字符：分页时要把顶层子目录名拼进命令，必须先卡住形状。 */
    private val SAFE_SEGMENT = Regex("^[0-9A-Za-z._-]{1,32}$")

    /** 分页用的单级目录名是否安全（不含分隔符、不是 `.`/`..`）。 */
    fun isSafeSegment(value: String): Boolean =
        SAFE_SEGMENT.matches(value) && value != "." && value != ".."

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
