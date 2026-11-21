package top.aico.wxcacheviewer.ui.common

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}

const val SHORT_HASH_LENGTH = 8

fun shortHash(hash: String): String =
    if (hash.length > SHORT_HASH_LENGTH) hash.take(SHORT_HASH_LENGTH) else hash

private const val PATTERN_SAME_YEAR = "MM-dd HH:mm"
private const val PATTERN_OTHER_YEAR = "yyyy-MM-dd"

/**
 * 把 Unix 秒格式化为便于阅读的修改时间：当年只显示月日与时分，跨年补上年份。
 * 取值不可用（<= 0）时返回空串，由调用方决定是否展示。
 */
fun formatModifiedAt(epochSeconds: Long, nowMillis: Long = System.currentTimeMillis()): String {
    if (epochSeconds <= 0L) return ""
    val date = Date(epochSeconds * 1000)
    val fileYear = Calendar.getInstance().apply { time = date }.get(Calendar.YEAR)
    val currentYear = Calendar.getInstance().apply { timeInMillis = nowMillis }.get(Calendar.YEAR)
    val pattern = if (fileYear == currentYear) PATTERN_SAME_YEAR else PATTERN_OTHER_YEAR
    return SimpleDateFormat(pattern, Locale.getDefault()).format(date)
}
