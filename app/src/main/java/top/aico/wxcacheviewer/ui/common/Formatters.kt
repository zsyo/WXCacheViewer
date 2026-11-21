package top.aico.wxcacheviewer.ui.common

fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}

const val SHORT_HASH_LENGTH = 8

fun shortHash(hash: String): String =
    if (hash.length > SHORT_HASH_LENGTH) hash.take(SHORT_HASH_LENGTH) else hash
