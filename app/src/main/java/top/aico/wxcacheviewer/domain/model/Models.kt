package top.aico.wxcacheviewer.domain.model

enum class FileType {
    MP4, JPEG, PNG, GIF, WEBP,

    /** 聊天文件（attachment）里收发的文档与安装包等。 */
    ZIP, PDF, DOC, DOCX, XLS, XLSX, PPT, PPTX, TXT, APK, AUDIO,

    /** 微信语音：`voice2` 下的 `.amr` 实际是 SILK v3，需要先解码成 PCM 才能播放。 */
    SILK,

    /** 真正的 AMR 语音，可直接播放。 */
    AMR,

    UNKNOWN
}

/** 导出与媒体库扫描用的 MIME 类型。 */
val FileType.mime: String
    get() = when (this) {
        FileType.MP4 -> "video/mp4"
        FileType.JPEG -> "image/jpeg"
        FileType.PNG -> "image/png"
        FileType.GIF -> "image/gif"
        FileType.WEBP -> "image/webp"
        FileType.ZIP -> "application/zip"
        FileType.PDF -> "application/pdf"
        FileType.DOC -> "application/msword"
        FileType.DOCX ->
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        FileType.XLS -> "application/vnd.ms-excel"
        FileType.XLSX ->
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        FileType.PPT -> "application/vnd.ms-powerpoint"
        FileType.PPTX ->
            "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        FileType.TXT -> "text/plain"
        FileType.APK -> "application/vnd.android.package-archive"
        FileType.AUDIO -> "audio/mpeg"
        FileType.SILK -> "audio/silk"
        FileType.AMR -> "audio/amr"
        FileType.UNKNOWN -> "application/octet-stream"
    }

/** 导出文件的扩展名（缓存本体多数没有扩展名，导出时必须补上）。 */
val FileType.extension: String
    get() = when (this) {
        FileType.MP4 -> "mp4"
        FileType.JPEG -> "jpg"
        FileType.PNG -> "png"
        FileType.GIF -> "gif"
        FileType.WEBP -> "webp"
        FileType.ZIP -> "zip"
        FileType.PDF -> "pdf"
        FileType.DOC -> "doc"
        FileType.DOCX -> "docx"
        FileType.XLS -> "xls"
        FileType.XLSX -> "xlsx"
        FileType.PPT -> "ppt"
        FileType.PPTX -> "pptx"
        FileType.TXT -> "txt"
        FileType.APK -> "apk"
        FileType.AUDIO -> "mp3"
        FileType.SILK -> "silk"
        FileType.AMR -> "amr"
        FileType.UNKNOWN -> "bin"
    }

/** 能交给图片预览页的类型。 */
val FileType.isImage: Boolean
    get() = this == FileType.JPEG || this == FileType.PNG ||
        this == FileType.GIF || this == FileType.WEBP

/** 语音类缓存，列表里走内联播放而不是预览或「打开方式」。 */
val FileType.isVoice: Boolean
    get() = this == FileType.SILK || this == FileType.AMR

data class Account(
    val hash: String,
    val hasVideoCache: Boolean
)

data class CacheFile(
    val accountHash: String,
    /** 文件名（不含目录），多选徽标与列表第二行展示用。 */
    val name: String,
    val size: Long,
    /** 文件最后修改时间，Unix 秒；无法获取时为 0。 */
    val modifiedAt: Long,
    val type: FileType,
    /** 相对所属缓存源根目录的路径，可能包含子目录；旧数据回退为文件名。 */
    val relativePath: String = name,
    /** 所属缓存源，见 SourceRegistry。 */
    val sourceId: String = "finder_video",
    /** 同目录下是否有同名 jpg 首帧（聊天视频）。 */
    val hasThumbnail: Boolean = false,
    /**
     * 文件头部被加密（表情目录里裸 32 位 md5 的那一类）。
     * 读取、预览、导出前都要先用 [top.aico.wxcacheviewer.domain.emoji.EmojiCrypto] 解开前 1024 字节。
     */
    val encrypted: Boolean = false
) {
    /**
     * 跨缓存源唯一的标识：列表 key、多选集合元素、图片缓存键都用它。
     * 递归源里不同子目录可能出现同名文件，不能再用 name 当主键。
     */
    val key: String get() = "$sourceId:$relativePath"
}

enum class SortField {
    SIZE, MODIFIED_TIME
}

enum class SortDirection {
    ASC, DESC
}

data class SortOption(
    val field: SortField = SortField.MODIFIED_TIME,
    val direction: SortDirection = SortDirection.DESC
)
