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
    /** 文件最后修改时间，Unix 秒；无法获取时为 0。 */
    val modifiedAt: Long,
    val type: FileType
)

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
