package top.aico.wxcacheviewer.domain.update

/**
 * 更新渠道。
 *
 * - [STABLE]：只看 GitHub 上被标记为 latest 的正式版本，预发布版一律不提示。
 * - [PRERELEASE]：在全部已发布版本里找最新的，只要有更新就提示。
 */
enum class UpdateChannel {
    STABLE,
    PRERELEASE
}
