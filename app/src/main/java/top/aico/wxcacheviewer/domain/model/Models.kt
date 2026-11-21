package top.aico.wxcacheviewer.domain.model

enum class FileType {
    MP4, JPEG, PNG, GIF, WEBP, UNKNOWN
}

data class Account(
    val hash: String,
    val hasVideoCache: Boolean
)

data class CacheFile(
    val accountHash: String,
    val name: String,
    val size: Long,
    val type: FileType
)
