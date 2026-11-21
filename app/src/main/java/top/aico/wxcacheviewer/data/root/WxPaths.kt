package top.aico.wxcacheviewer.data.root

object WxPaths {
    const val CACHE_ROOT = "/data/user/0/com.tencent.mm/cache"
    const val IDENTITY_ROOT = "/data/user/0/com.tencent.mm/files/identity_records"

    private val accountHashRegex = Regex("^[0-9a-f]{32}$")
    private val cacheFileNameRegex = Regex("^finder_video(?:_xWT[0-9]+)?__?[0-9a-f]{32}$")

    fun isValidAccountHash(value: String): Boolean = accountHashRegex.matches(value)

    fun isValidCacheFileName(value: String): Boolean = cacheFileNameRegex.matches(value)

    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    fun videoDir(hash: String): String {
        require(isValidAccountHash(hash)) { "Invalid account hash: $hash" }
        return "$CACHE_ROOT/$hash/finder/video"
    }

    fun discoverAccountsCommand(): String = listOf(
        "for d in ${shellQuote(CACHE_ROOT)} ${shellQuote(IDENTITY_ROOT)}; do",
        "  [ -d \"\$d\" ] || continue",
        "  ls -1 \"\$d\" 2>/dev/null",
        "done | grep -E '^[0-9a-f]{32}$' | sort -u || true"
    ).joinToString("\n")

    fun checkVideoDirsCommand(hashes: List<String>): String {
        val valid = hashes.filter(::isValidAccountHash)
        if (valid.isEmpty()) return "exit 0"
        val checks = valid.joinToString("\n") { hash ->
            "[ -d ${shellQuote("$CACHE_ROOT/$hash/finder/video")} ] && echo $hash"
        }
        return "$checks\ntrue"
    }

    fun listFilesCommand(hash: String): String {
        val dir = shellQuote(videoDir(hash))
        return listOf(
            "for f in $dir/*; do",
            "  [ -f \"\$f\" ] || continue",
            "  printf '%s\\t%s\\t' \"\$(basename \"\$f\")\" \"\$(stat -c %s \"\$f\")\"",
            "  head -c 32 \"\$f\" | od -An -v -tx1 | tr -d ' \\n'",
            "  printf '\\n'",
            "done"
        ).joinToString("\n")
    }

    fun copyFileCommand(hash: String, name: String, destination: String): String {
        require(isValidCacheFileName(name)) { "Invalid cache file name: $name" }
        val source = "${videoDir(hash)}/$name"
        val quotedDestination = shellQuote(destination)
        return "cp ${shellQuote(source)} $quotedDestination && chmod 0644 $quotedDestination"
    }
}
