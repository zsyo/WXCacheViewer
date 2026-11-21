package top.aico.wxcacheviewer.data.root

import top.aico.wxcacheviewer.domain.model.Account

/**
 * 解析账号发现命令的输出。
 *
 * 命令输出两类行：
 * 1. 以 /finder/video 结尾的绝对路径，表示该账号存在视频缓存目录；
 * 2. 单个 32 位十六进制名（cache 下的目录或 identity_records 下的条目）。
 */
object AccountDiscoveryParser {

    private const val VIDEO_SUFFIX = "/finder/video"
    private const val VIDEO_DIR_NAME = "video"
    private const val FINDER_DIR_NAME = "finder"
    private const val HASH_LENGTH = 32

    fun parse(stdout: String): List<Account> {
        val withVideo = LinkedHashSet<String>()
        val hashes = LinkedHashSet<String>()

        stdout.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            if (line.endsWith(VIDEO_SUFFIX)) {
                hashOfVideoPath(line)?.let {
                    withVideo += it
                    hashes += it
                }
            } else {
                val name = line.substringAfterLast('/')
                if (isHash(name)) hashes += name
            }
        }

        return hashes.map { Account(it, it in withVideo) }
            .sortedWith(compareByDescending<Account> { it.hasVideoCache }.thenBy { it.hash })
    }

    private fun hashOfVideoPath(path: String): String? {
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.size < 4) return null
        if (segments[segments.lastIndex] != VIDEO_DIR_NAME) return null
        if (segments[segments.lastIndex - 1] != FINDER_DIR_NAME) return null
        val hash = segments[segments.lastIndex - 2]
        return if (isHash(hash)) hash else null
    }

    private fun isHash(value: String): Boolean =
        value.length == HASH_LENGTH && value.all { it in '0'..'9' || it in 'a'..'f' }
}
