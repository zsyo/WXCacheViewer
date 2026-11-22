package top.aico.wxcacheviewer.domain.update

/**
 * 发布版本号：`major.minor.patch[-prerelease][+build]`。
 *
 * 只实现比较需要的部分，不追求严格 semver 校验：允许 `v` 前缀、允许省略 patch（`0.1`），
 * 这样既能比较 tag（`v0.1.1`），也能比较本地构建的 versionName。
 */
data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    /** 预发布段，`1.0.0-beta.2` 拆成 `[beta, 2]`；空表示正式版。 */
    val preRelease: List<String> = emptyList()
) : Comparable<SemanticVersion> {

    override fun compareTo(other: SemanticVersion): Int {
        val core = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
        if (core != 0) return core
        // 同核心版本时预发布版更旧：1.0.0-beta < 1.0.0
        if (preRelease.isEmpty() && other.preRelease.isEmpty()) return 0
        if (preRelease.isEmpty()) return 1
        if (other.preRelease.isEmpty()) return -1
        val shared = minOf(preRelease.size, other.preRelease.size)
        for (index in 0 until shared) {
            val result = compareIdentifier(preRelease[index], other.preRelease[index])
            if (result != 0) return result
        }
        return preRelease.size.compareTo(other.preRelease.size)
    }

    /** 数字段按数值比较且优先级低于字母段，其余按字典序。 */
    private fun compareIdentifier(left: String, right: String): Int {
        val leftNumber = left.toLongOrNull()
        val rightNumber = right.toLongOrNull()
        return when {
            leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
            leftNumber != null -> -1
            rightNumber != null -> 1
            else -> left.compareTo(right)
        }
    }

    override fun toString(): String = buildString {
        append(major).append('.').append(minor).append('.').append(patch)
        if (preRelease.isNotEmpty()) append('-').append(preRelease.joinToString("."))
    }

    companion object {
        /** 无法识别的版本号（如手动触发构建的 `dev-1a2b3c4`）返回 null。 */
        fun parse(raw: String): SemanticVersion? {
            var text = raw.trim()
            if (text.isEmpty()) return null
            if (text.startsWith("v", ignoreCase = true)) text = text.substring(1)
            // +build 只是元数据，不参与比较
            text = text.substringBefore('+')
            val dash = text.indexOf('-')
            val core = if (dash >= 0) text.substring(0, dash) else text
            val pre = if (dash >= 0) text.substring(dash + 1) else ""
            val parts = core.split('.')
            if (parts.size > 3) return null
            val numbers = parts.map { it.toIntOrNull() ?: return null }
            if (numbers.any { it < 0 }) return null
            return SemanticVersion(
                major = numbers.getOrElse(0) { 0 },
                minor = numbers.getOrElse(1) { 0 },
                patch = numbers.getOrElse(2) { 0 },
                preRelease = if (pre.isEmpty()) emptyList() else pre.split('.').filter { it.isNotEmpty() }
            )
        }
    }
}
