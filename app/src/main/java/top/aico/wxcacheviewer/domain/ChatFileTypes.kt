package top.aico.wxcacheviewer.domain

import top.aico.wxcacheviewer.domain.model.FileType

/**
 * 聊天文件按扩展名推断类型。
 *
 * 这类文件都带原始扩展名，比读文件头更准：docx/xlsx/pptx 与普通 zip 的魔数完全相同，
 * 只看魔数会把它们全认成压缩包。无扩展名的文件（微信偶有）交给界面显示成「文件」。
 */
object ChatFileTypes {

    fun fromName(name: String): FileType? {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.lastIndex) return null
        val extension = name.substring(dot + 1).lowercase()
        if (extension.length > 5) return null
        return when (extension) {
            "zip" -> FileType.ZIP
            "pdf" -> FileType.PDF
            "doc" -> FileType.DOC
            "docx" -> FileType.DOCX
            "xls" -> FileType.XLS
            "xlsx" -> FileType.XLSX
            "ppt" -> FileType.PPT
            "pptx" -> FileType.PPTX
            "txt", "md", "log", "csv", "json", "xml" -> FileType.TXT
            "apk" -> FileType.APK
            "mp3", "m4a", "amr", "aac", "wav", "ogg", "flac" -> FileType.AUDIO
            "mp4", "m4v" -> FileType.MP4
            "jpg", "jpeg" -> FileType.JPEG
            "png" -> FileType.PNG
            "gif" -> FileType.GIF
            "webp" -> FileType.WEBP
            else -> null
        }
    }

    /** 列表徽标用的短标签：优先显示原扩展名，没有扩展名时给个通用标识。 */
    fun badge(name: String, type: FileType): String {
        val dot = name.lastIndexOf('.')
        if (dot in 1 until name.lastIndex) {
            val extension = name.substring(dot + 1)
            if (extension.length <= 4) return extension.uppercase()
        }
        return when (type) {
            FileType.MP4 -> "视频"
            FileType.JPEG, FileType.PNG, FileType.GIF, FileType.WEBP -> "图片"
            FileType.AUDIO -> "音频"
            FileType.APK -> "APK"
            else -> "文件"
        }
    }
}
