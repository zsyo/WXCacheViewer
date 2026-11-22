package top.aico.wxcacheviewer.domain.update

/** Release 里的一个安装包附件，CI 按 ABI 拆成四个，名字形如 `WXCacheViewer-0.1.1-arm64-v8a.apk`。 */
data class ReleaseAsset(
    val name: String,
    val downloadUrl: String
)

/** 一条 GitHub Release 摘要，只保留检查更新需要的字段。 */
data class ReleaseInfo(
    val tagName: String,
    val version: SemanticVersion,
    val prerelease: Boolean,
    /** 发布日期，取 `yyyy-MM-dd`；GitHub 的原始值是 ISO 8601 时间串。 */
    val publishedAt: String,
    val htmlUrl: String,
    val notes: String,
    val assets: List<ReleaseAsset> = emptyList()
) {
    /**
     * 取与本机 ABI 匹配的安装包直链；没有匹配项时退回 Release 页面，
     * 让用户自己挑架构，避免把 x86 包装到 arm 设备上。
     */
    fun downloadUrlFor(abis: List<String>): String {
        for (abi in abis) {
            assets.firstOrNull { it.name.endsWith("-$abi.apk", ignoreCase = true) }
                ?.let { return it.downloadUrl }
        }
        return htmlUrl
    }
}
