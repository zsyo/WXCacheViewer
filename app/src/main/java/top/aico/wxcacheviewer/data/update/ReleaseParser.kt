package top.aico.wxcacheviewer.data.update

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import top.aico.wxcacheviewer.domain.update.ReleaseAsset
import top.aico.wxcacheviewer.domain.update.ReleaseInfo
import top.aico.wxcacheviewer.domain.update.SemanticVersion

/**
 * GitHub Releases 的 JSON 解析。
 *
 * 单独抽出来是为了能脱离网络做单测：只要给一段响应文本就能验字段映射与版本挑选。
 */
internal object ReleaseParser {

    /** 列表接口的响应；单条解析失败（草稿、tag 不像版本号）时跳过而不是整体失败。 */
    fun parseReleases(body: String): List<ReleaseInfo> {
        val array = try {
            JSONArray(body)
        } catch (e: JSONException) {
            throw UpdateCheckException(UpdateError.PARSE)
        }
        val releases = ArrayList<ReleaseInfo>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            parseRelease(item)?.let(releases::add)
        }
        return releases
    }

    fun parseRelease(json: JSONObject): ReleaseInfo? {
        // 草稿不对外可见，不该参与比较
        if (json.optBoolean("draft")) return null
        val tag = json.optString("tag_name")
        val version = SemanticVersion.parse(tag) ?: return null
        val assetsJson = json.optJSONArray("assets")
        val assets = ArrayList<ReleaseAsset>()
        if (assetsJson != null) {
            for (index in 0 until assetsJson.length()) {
                val asset = assetsJson.optJSONObject(index) ?: continue
                val name = asset.optString("name")
                val url = asset.optString("browser_download_url")
                if (name.isNotEmpty() && url.isNotEmpty()) {
                    assets += ReleaseAsset(name = name, downloadUrl = url)
                }
            }
        }
        return ReleaseInfo(
            tagName = tag,
            version = version,
            prerelease = json.optBoolean("prerelease"),
            publishedAt = json.optString("published_at").take(10),
            htmlUrl = json.optString("html_url"),
            notes = json.optString("body").trim(),
            assets = assets
        )
    }

    /** 版本号最大者；同一版本（理论上不该出现）时取发布更晚的。 */
    fun newest(releases: List<ReleaseInfo>): ReleaseInfo? =
        releases.maxWithOrNull(compareBy<ReleaseInfo> { it.version }.thenBy { it.publishedAt })
}
