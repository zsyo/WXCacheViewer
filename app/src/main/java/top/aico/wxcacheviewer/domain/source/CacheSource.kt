package top.aico.wxcacheviewer.domain.source

import top.aico.wxcacheviewer.domain.model.FileType

/** 微信数据根目录的两种写法：`cache/<hash>/...` 与 `MicroMsg/<hash>/...`。 */
enum class SourceRoot(val dirName: String) {
    CACHE("cache"),
    MICROMSG("MicroMsg")
}

/** 导出目标：视频进 Movies，图片进 Pictures，文档进 Download，语音进 Music。 */
enum class ExportKind {
    MOVIES, PICTURES, DOWNLOADS, MUSIC
}

/** 扁平源列表失败后改用 `wc -c` 重试时使用的默认超时。 */
const val DEFAULT_LIST_TIMEOUT_MS = 15_000L

/** 递归源需要遍历多层目录，超时给得更宽。 */
const val RECURSIVE_LIST_TIMEOUT_MS = 30_000L

/**
 * 一类微信缓存文件的描述。
 *
 * 所有文件操作都以「账号目录 + 相对路径」定位。相对路径必须同时通过
 * [relativePathRegex] 与 [SourceRegistry.isValidRelativePath] 的校验，
 * 否则拼接后可能变成任意路径读写。
 *
 * @param globs 扁平源的文件名通配符，如 `*`、`*.mp4`、`*_gif`
 * @param findNames 递归源的 `find -name` 模式，如 `th_*`
 * @param probeHeader 是否读取文件头 32 字节用于识别类型；关闭时按文件名推断
 * @param typeHint 拿不到文件头时按文件名推断类型；聊天文件只看扩展名
 * @param allowDelete 是否允许删除；微信运行中还会写回的目录设为 false
 * @param pageSize 每页条数。扁平源按文件名倒序切片，递归源一个一级子目录算一页
 * @param preserveFileName 导出时保留原文件名（聊天文件），否则用「源前缀 + 时间 + 摘要」重命名
 * @param encryptedName 需要解密的文件名形状（表情目录里裸 32 位 md5 的头部是 AES 密文）
 * @param skipSiblingSuffix 存在同名的「本名 + 该后缀」文件时跳过本文件；
 *   表情的裸 md5 与 `<md5>_gif` 解密后内容完全相同，只留一份
 */
data class CacheSource(
    val id: String,
    val title: String,
    val root: SourceRoot,
    val relativeDir: String,
    val relativePathRegex: Regex,
    val exportTag: String,
    val exportKind: ExportKind,
    val recursive: Boolean = false,
    val maxDepth: Int = 3,
    val globs: List<String> = emptyList(),
    val findNames: List<String> = emptyList(),
    val probeHeader: Boolean = false,
    /** 同目录下是否存在同名 `.jpg` 首帧（聊天视频），列目录时顺带探测。 */
    val hasJpegThumbnail: Boolean = false,
    val typeHint: (String) -> FileType? = { null },
    val allowDelete: Boolean = true,
    val pageSize: Int = 60,
    val preserveFileName: Boolean = false,
    val encryptedName: Regex? = null,
    val skipSiblingSuffix: String? = null,
    val listTimeoutMs: Long = DEFAULT_LIST_TIMEOUT_MS
)
