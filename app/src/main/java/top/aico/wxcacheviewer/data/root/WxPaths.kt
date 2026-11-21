package top.aico.wxcacheviewer.data.root

object WxPaths {
    const val CACHE_ROOT = "/data/user/0/com.tencent.mm/cache"
    const val IDENTITY_ROOT = "/data/user/0/com.tencent.mm/files/identity_records"
    const val MICROMSG_ROOT = "/data/user/0/com.tencent.mm/MicroMsg"
    const val LOGIN_PREFS_FILE =
        "/data/user/0/com.tencent.mm/shared_prefs/com.tencent.mm_preferences.xml"

    /** 每个账号目录下自带一份视频号资料库，明文 SQLite，可用于识别该账号的昵称。 */
    const val FINDER_ACCOUNT_DB = "FinderAccounts_01.db"

    const val MISSING_MARK = "MISSING"

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

    /**
     * 把账号目录下的 FinderAccounts_01.db 复制到应用私有目录（目录由应用预先创建）。
     *
     * WAL 文件里可能还有未合并的写入，缺了它容易读到空表，因此 -wal、-shm 一起复制；
     * 复制件属主是 root，逐个改成 0666 让应用进程可以打开（SQLite 在 WAL 模式下要写 -shm）。
     * 源文件不存在时输出 MISSING，便于与其它失败区分。
     */
    fun copyFinderAccountDbCommand(hash: String, destinationDir: String): String {
        require(isValidAccountHash(hash)) { "Invalid account hash: $hash" }
        val source = "$MICROMSG_ROOT/$hash/$FINDER_ACCOUNT_DB"
        val destination = "$destinationDir/$FINDER_ACCOUNT_DB"
        return listOf(
            "[ -f ${shellQuote(source)} ] || { echo $MISSING_MARK; exit 0; }",
            "cp -f ${shellQuote(source)} ${shellQuote(destination)}",
            "chmod 0666 ${shellQuote(destination)}",
            "if [ -f ${shellQuote("$source-wal")} ]; then " +
                "cp -f ${shellQuote("$source-wal")} ${shellQuote("$destination-wal")} && " +
                "chmod 0666 ${shellQuote("$destination-wal")}; fi",
            "if [ -f ${shellQuote("$source-shm")} ]; then " +
                "cp -f ${shellQuote("$source-shm")} ${shellQuote("$destination-shm")} && " +
                "chmod 0666 ${shellQuote("$destination-shm")}; fi",
            "echo OK"
        ).joinToString("\n")
    }

    /**
     * 只摘出「最后登录账号」的昵称与 uin。整份 prefs 里还有手机号等敏感字段，
     * 用 grep 定点取值，避免把整个文件读进内存或写进日志。
     */
    fun readLoginPrefsCommand(): String {
        val file = shellQuote(LOGIN_PREFS_FILE)
        return LOGIN_PREF_FIELDS.joinToString("\n") { field ->
            "grep -o " + shellQuote("name=\"$field\">[^<]*") + " $file 2>/dev/null || true"
        }
    }

    private val LOGIN_PREF_FIELDS = listOf("last_login_nick_name", "last_login_uin")
}
