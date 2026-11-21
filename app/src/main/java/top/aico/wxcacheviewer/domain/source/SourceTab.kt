package top.aico.wxcacheviewer.domain.source

/**
 * 相册页顶部的标签页。一个标签页可以对应多个缓存源（例如「图片」同时含聊天图片与朋友圈）。
 */
enum class SourceTab(val id: String, val title: String) {
    FINDER("finder", "视频号"),
    CHAT_VIDEO("chat_video", "聊天视频"),
    EMOJI("emoji", "表情"),
    IMAGES("images", "图片"),
    FILES("files", "文件");

    val sources: List<CacheSource>
        get() = when (this) {
            FINDER -> listOf(SourceRegistry.FINDER_VIDEO)
            CHAT_VIDEO -> listOf(SourceRegistry.CHAT_VIDEO)
            EMOJI -> listOf(SourceRegistry.EMOJI)
            IMAGES -> listOf(SourceRegistry.CHAT_IMAGE, SourceRegistry.SNS)
            FILES -> listOf(SourceRegistry.CHAT_FILE)
        }

    /** 该标签页内是否允许删除；只读源（聊天图片、朋友圈、聊天文件）不显示删除入口。 */
    val allowsDelete: Boolean
        get() = sources.all { it.allowDelete }

    /** 文档类内容用单列列表展示，缩略图网格看文件名太小。 */
    val listOnly: Boolean
        get() = this == FILES

    companion object {
        fun fromId(id: String?): SourceTab = entries.firstOrNull { it.id == id } ?: FINDER
    }
}
