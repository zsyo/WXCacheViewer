package top.aico.wxcacheviewer.data.root

import top.aico.wxcacheviewer.domain.source.CacheSource
import top.aico.wxcacheviewer.domain.source.SourceRegistry
import top.aico.wxcacheviewer.domain.source.SourceRoot

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

    /** 一页扫描的行数上限：单目录一般几十个文件，超出的先截断，避免极端目录拖慢一页。 */
    private const val MAX_PAGE_SCAN_LINES = 400

    /** 每次 stat 调用处理的文件数。批量调用远比逐个 fork 快，几千个文件也不至于超时。 */
    private const val STAT_BATCH_SIZE = 200

    /** 扁平源一页的条数上限，防止调用方传一个荒唐的 limit 把内存撑爆。 */
    private const val MAX_PAGE_LIMIT = 500

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

    /** 缓存源在账号下的绝对目录，例如 `cache/<hash>/finder/video`、`MicroMsg/<hash>/emoji`。 */
    fun sourceDir(hash: String, source: CacheSource): String {
        require(isValidAccountHash(hash)) { "Invalid account hash: $hash" }
        val root = when (source.root) {
            SourceRoot.CACHE -> CACHE_ROOT
            SourceRoot.MICROMSG -> MICROMSG_ROOT
        }
        return "$root/$hash/${source.relativeDir}"
    }

    /**
     * 相对路径拼接成绝对路径。相对路径必须通过缓存源的白名单校验，
     * 拼接后再断言一次前缀，防止出现 `..` 之类的越界写法。
     */
    fun sourceFilePath(hash: String, source: CacheSource, relativePath: String): String {
        require(SourceRegistry.isValidRelativePath(source, relativePath)) {
            "Invalid relative path: $relativePath"
        }
        val base = sourceDir(hash, source)
        val full = "$base/$relativePath"
        require(full.startsWith("$base/")) { "Invalid relative path: $relativePath" }
        return full
    }

    /**
     * 扁平源（视频号、聊天视频、表情）的一次性列表，不分页。
     * 界面已改用 [listSourcePageCommand]，这里保留给全量场景与测试。
     */
    fun listSourceFilesCommand(
        hash: String,
        source: CacheSource,
        useWcForSize: Boolean = false
    ): String {
        require(!source.recursive) { "Recursive source must be paged: ${source.id}" }
        return flatListSourceCommand(hash, source, useWcForSize)
    }

    /**
     * 扁平源的一页：按文件名倒序，跳过 [offset] 条后取 [limit] 条。
     *
     * 目录里有几千个文件时，逐个 `stat` 的老写法要 fork 几千次进程，切个标签页就要等十几秒。
     * 这里把「取哪一批」交给纯 shell 计数（`ls` 一次 + 循环内建判断，不 fork），
     * 只对本页这几十个文件执行 `stat`，首屏开销与文件总数脱钩。
     *
     * 文件名倒序对带时间戳的源（聊天视频 `2609251653228091.mp4`）就是「最新在前」，
     * 与界面默认的时间倒序一致；md5 命名的源顺序无语义，但不影响正确性。
     */
    fun listSourcePageCommand(
        hash: String,
        source: CacheSource,
        offset: Int,
        limit: Int,
        useWcForSize: Boolean = false
    ): String {
        require(!source.recursive) { "Recursive source must be paged by dir: ${source.id}" }
        require(offset >= 0) { "Invalid page offset: $offset" }
        require(limit in 1..MAX_PAGE_LIMIT) { "Invalid page limit: $limit" }

        val dir = shellQuote(sourceDir(hash, source))
        val patterns = source.globs.joinToString("|")
        val sizeExpression = sizeExpression(useWcForSize)
        val headerExpression = headerExpression(source)
        val thumbExpression = thumbExpression(source)
        val skipExpression = skipSiblingExpression(source)
        val end = offset + limit
        return listOfNotNull(
            "D=$dir",
            "[ -d \"\$D\" ] || exit 0",
            "i=0",
            "ls -1r \"\$D\" 2>/dev/null | while IFS= read -r n; do",
            "  case \"\$n\" in $patterns) ;; *) continue ;; esac",
            "  f=\"\$D/\$n\"",
            "  [ -f \"\$f\" ] || continue",
            skipExpression,
            "  i=\$((i+1))",
            "  if [ \"\$i\" -le $offset ]; then continue; fi",
            "  if [ \"\$i\" -gt $end ]; then break; fi",
            "  printf '%s\\t%s\\t%s\\t' \"\$n\" \"\$($sizeExpression)\" \"\$(stat -c %Y \"\$f\" 2>/dev/null || echo 0)\"",
            "  $headerExpression",
            "  printf '\\t%s\\t%s\\n' \"\$n\" \"$thumbExpression\"",
            "done"
        ).joinToString("\n")
    }

    /**
     * 「已有明文兄弟就跳过」的 shell 片段：表情裸 md5 与 `<md5>_gif` 解密后内容相同，
     * 让 shell 在计数前就跳过，页内条数与游标才对得上。
     */
    private fun skipSiblingExpression(source: CacheSource): String? =
        source.skipSiblingSuffix?.let { suffix ->
            "  if [ -f \"\$D/\${n}$suffix\" ]; then continue; fi"
        }

    private fun flatListSourceCommand(
        hash: String,
        source: CacheSource,
        useWcForSize: Boolean
    ): String {
        val dir = shellQuote(sourceDir(hash, source))
        val patterns = source.globs.joinToString(" ") { "$dir/$it" }
        val skipExpression = source.skipSiblingSuffix?.let { suffix ->
            "  if [ -f \"\$D/\${f##*/}$suffix\" ]; then continue; fi"
        }
        return listOfNotNull(
            "D=$dir",
            "for f in $patterns; do",
            "  [ -f \"\$f\" ] || continue",
            skipExpression,
            "  printf '%s\\t%s\\t%s\\t' \"\$(basename \"\$f\")\" \"\$(${sizeExpression(useWcForSize)})\" \"\$(stat -c %Y \"\$f\" 2>/dev/null || echo 0)\"",
            "  ${headerExpression(source)}",
            "  printf '\\t%s\\t%s\\n' \"\$(basename \"\$f\")\" \"${thumbExpression(source)}\"",
            "done"
        ).joinToString("\n")
    }

    private fun sizeExpression(useWcForSize: Boolean): String = if (useWcForSize) {
        "\$(wc -c < \"\$f\")"
    } else {
        "stat -c %s \"\$f\" 2>/dev/null || wc -c < \"\$f\""
    }

    private fun headerExpression(source: CacheSource): String = if (source.probeHeader) {
        "head -c 32 \"\$f\" | od -An -v -tx1 | tr -d ' \\n'"
    } else {
        "printf '%s' '-'"
    }

    private fun thumbExpression(source: CacheSource): String = if (source.hasJpegThumbnail) {
        "\$([ -f \"\${f%.mp4}.jpg\" ] && echo 1 || echo 0)"
    } else {
        "0"
    }

    /**
     * 递归源的一级子目录，带修改时间：`mtime 路径` 每行一条。
     *
     * `image2` 这类目录有一级 256 个、二级上万个，整棵树 `find` 一次要几十秒，
     * 因此改成按一级子目录分页扫描；一级目录按 mtime 倒序，让最近用过的排前面。
     */
    fun listSourceSubDirsCommand(hash: String, source: CacheSource): String {
        require(source.recursive) { "Flat source has no sub dirs: ${source.id}" }
        val dir = shellQuote(sourceDir(hash, source))
        return listOf(
            "D=$dir",
            "[ -d \"\$D\" ] || exit 0",
            "stat -c '%Y %n' \"\$D\"/*/ 2>/dev/null"
        ).joinToString("\n")
    }

    /** 兜底方案：拿不到 mtime 时（stat 不支持 %n）只列目录名。 */
    fun listSourceSubDirNamesCommand(hash: String, source: CacheSource): String {
        require(source.recursive) { "Flat source has no sub dirs: ${source.id}" }
        val dir = shellQuote(sourceDir(hash, source))
        return listOf(
            "D=$dir",
            "[ -d \"\$D\" ] || exit 0",
            "ls -1 \"\$D\" 2>/dev/null"
        ).joinToString("\n")
    }

    /**
     * 递归源的「一页」：只扫一个一级子目录。
     *
     * 单目录几十个文件，`find | xargs stat` 一次就完，耗时几十毫秒；整棵树拆成
     * 几百次这样的调用，由界面滚到底部时逐页触发，用户不必等一次全量扫描。
     */
    fun listSourceDirPageCommand(
        hash: String,
        source: CacheSource,
        dirName: String
    ): String {
        require(source.recursive) { "Flat source cannot be paged: ${source.id}" }
        require(SourceRegistry.isSafeSegment(dirName)) { "Invalid dir name: $dirName" }
        val base = shellQuote(sourceDir(hash, source))
        val pageDir = shellQuote("${sourceDir(hash, source)}/$dirName")
        val names = source.findNames.joinToString(" -o ") { "-name ${shellQuote(it)}" }
        val nameFilter = if (names.isEmpty()) "" else " \\( $names \\)"
        val depth = (source.maxDepth - 1).coerceAtLeast(1)
        return listOf(
            "D=$base",
            "P=$pageDir",
            "[ -d \"\$P\" ] || exit 0",
            "find \"\$P\" -maxdepth $depth -type f$nameFilter 2>/dev/null" +
                " | head -n $MAX_PAGE_SCAN_LINES" +
                " | tr '\\n' '\\0'" +
                " | xargs -0 -n $STAT_BATCH_SIZE stat -c '%s %Y %n' 2>/dev/null" +
                // 注意用默认 IFS：写成 `IFS= read` 会把分隔符置空，read 就不拆字段了，
                // 整行全落进 $sz，导致后面的列全部错位。
                " | while read -r sz mt path; do",
            "  rel=\${path#\"\$D\"/}",
            "  printf '%s\\t%s\\t%s\\t-\\t%s\\t0\\n' \"\${rel##*/}\" \"\$sz\" \"\$mt\" \"\$rel\"",
            "done"
        ).joinToString("\n")
    }

    fun copySourceFileCommand(
        hash: String,
        source: CacheSource,
        relativePath: String,
        destination: String
    ): String {
        val sourcePath = shellQuote(sourceFilePath(hash, source, relativePath))
        val quotedDestination = shellQuote(destination)
        return "cp $sourcePath $quotedDestination && chmod 0644 $quotedDestination"
    }

    /**
     * 复制聊天视频的同名 jpg 首帧，省去解码视频取帧的开销。
     * 首帧不存在时输出 MISSING，调用方据此回退到视频首帧。
     */
    fun copyChatVideoThumbnailCommand(
        hash: String,
        relativePath: String,
        destination: String
    ): String {
        require(SourceRegistry.CHAT_VIDEO.relativePathRegex.matches(relativePath)) {
            "Invalid chat video name: $relativePath"
        }
        val jpgPath = shellQuote(
            "${sourceDir(hash, SourceRegistry.CHAT_VIDEO)}/${relativePath.removeSuffix(".mp4")}.jpg"
        )
        val quotedDestination = shellQuote(destination)
        return listOf(
            "[ -f $jpgPath ] || { echo $MISSING_MARK; exit 0; }",
            "cp -f $jpgPath $quotedDestination && chmod 0644 $quotedDestination && echo OK"
        ).joinToString("\n")
    }

    /** 删除单个缓存文件。成功后输出 OK，便于与“命令失败但退出码为 0”区分。 */
    fun deleteSourceFileCommand(
        hash: String,
        source: CacheSource,
        relativePath: String
    ): String {
        require(source.allowDelete) { "Source does not allow delete: ${source.id}" }
        val path = shellQuote(sourceFilePath(hash, source, relativePath))
        return "rm -f $path && if [ -e $path ]; then echo FAILED; else echo OK; fi"
    }

    /** 把缓存文件导出到 App 以外的目录（由 root 创建目录并复制）。 */
    fun exportSourceFileCommand(
        hash: String,
        source: CacheSource,
        relativePath: String,
        destination: String
    ): String {
        val sourcePath = shellQuote(sourceFilePath(hash, source, relativePath))
        val dest = shellQuote(destination)
        val destDir = shellQuote(destination.substringBeforeLast('/'))
        return "mkdir -p $destDir && cp -f $sourcePath $dest && chmod 0644 $dest && echo OK"
    }

    /**
     * 把应用私有目录里的文件（例如解码后的语音）由 root 复制到公共目录。
     * 应用自身在 Android 10+ 写不了公共目录，只能借 root 落盘。
     */
    fun exportLocalFileCommand(sourcePath: String, destination: String): String {
        val src = shellQuote(sourcePath)
        val dest = shellQuote(destination)
        val destDir = shellQuote(destination.substringBeforeLast('/'))
        return "mkdir -p $destDir && cp -f $src $dest && chmod 0644 $dest && echo OK"
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

    /**
     * 只取最后登录账号的 uin，供表情解密推导密钥。
     *
     * 与 [readLoginPrefsCommand] 分开是为了少读字段：表情解密只关心 uin，
     * 不需要把昵称一起带出来。
     */
    fun readLoginUinCommand(): String {
        val file = shellQuote(LOGIN_PREFS_FILE)
        return "grep -o " + shellQuote("name=\"last_login_uin\">[0-9]*") + " $file 2>/dev/null || true"
    }

    private val LOGIN_PREF_FIELDS = listOf("last_login_nick_name", "last_login_uin")
}
