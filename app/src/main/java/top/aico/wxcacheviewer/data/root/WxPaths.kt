package top.aico.wxcacheviewer.data.root

object WxPaths {
    const val CACHE_ROOT = "/data/user/0/com.tencent.mm/cache"
    const val IDENTITY_ROOT = "/data/user/0/com.tencent.mm/files/identity_records"

    /**
     * 微信数据目录的两种等价写法：/data/data 与 /data/user/0 在多数设备上是同一个目录，
     * 但个别机型对其中一种设置了额外的访问限制，因此两个都尝试。
     */
    val WX_BASES = listOf(
        "/data/data/com.tencent.mm",
        "/data/user/0/com.tencent.mm"
    )

    private val accountHashRegex = Regex("^[0-9a-f]{32}$")
    private val cacheFileNameRegex = Regex("^finder_video(?:_xWT[0-9]+)?__?[0-9a-f]{32}$")

    fun isValidAccountHash(value: String): Boolean = accountHashRegex.matches(value)

    fun isValidCacheFileName(value: String): Boolean = cacheFileNameRegex.matches(value)

    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    fun videoDir(hash: String): String {
        require(isValidAccountHash(hash)) { "Invalid account hash: $hash" }
        return "$CACHE_ROOT/$hash/finder/video"
    }

    /**
     * 列出账号身份目录与视频缓存目录。使用 find 而不是 ls，避免部分机型上 ls 走 shell
     * 别名/权限差异导致空输出；stderr 全部丢弃，输出由 AccountDiscoveryParser 解析。
     */
    fun discoverAccountsCommand(): String = buildString {
        appendLine("for base in " + WX_BASES.joinToString(" ") { shellQuote(it) } + "; do")
        appendLine("  find \"\$base/cache\" -maxdepth 3 -type d -name video 2>/dev/null")
        appendLine("  find \"\$base/cache\" -maxdepth 1 -type d 2>/dev/null")
        appendLine("  find \"\$base/files/identity_records\" -maxdepth 1 2>/dev/null")
        appendLine("done")
        append("true")
    }

    fun listFilesCommand(hash: String, useWcForSize: Boolean = false): String {
        val dir = shellQuote(videoDir(hash))
        val sizeExpression = if (useWcForSize) {
            "\$(wc -c < \"\$f\")"
        } else {
            "\$(stat -c %s \"\$f\")"
        }
        return listOf(
            "for f in $dir/*; do",
            "  [ -f \"\$f\" ] || continue",
            "  printf '%s\\t%s\\t%s\\t' \"\$(basename \"\$f\")\" \"$sizeExpression\" \"\$(stat -c %Y \"\$f\" 2>/dev/null || echo 0)\"",
            "  head -c 32 \"\$f\" | od -An -v -tx1 | tr -d ' \\n'",
            "  printf '\\n'",
            "done"
        ).joinToString("\n")
    }

    fun cacheFilePath(hash: String, name: String): String {
        require(isValidCacheFileName(name)) { "Invalid cache file name: $name" }
        return "${videoDir(hash)}/$name"
    }

    fun copyFileCommand(hash: String, name: String, destination: String): String {
        val source = shellQuote(cacheFilePath(hash, name))
        val quotedDestination = shellQuote(destination)
        return "cp $source $quotedDestination && chmod 0644 $quotedDestination"
    }

    /** 删除单个缓存文件。成功后输出 OK，便于与“命令失败但退出码为 0”区分。 */
    fun deleteFileCommand(hash: String, name: String): String {
        val path = shellQuote(cacheFilePath(hash, name))
        return "rm -f $path && if [ -e $path ]; then echo FAILED; else echo OK; fi"
    }

    /** 把缓存文件导出到 App 以外的目录（由 root 创建目录并复制）。 */
    fun exportFileCommand(hash: String, name: String, destination: String): String {
        val source = shellQuote(cacheFilePath(hash, name))
        val dest = shellQuote(destination)
        val destDir = shellQuote(destination.substringBeforeLast('/'))
        return "mkdir -p $destDir && cp -f $source $dest && chmod 0644 $dest && echo OK"
    }
}
