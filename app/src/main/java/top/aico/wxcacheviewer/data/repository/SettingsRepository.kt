package top.aico.wxcacheviewer.data.repository

import android.content.Context
import android.os.Environment
import top.aico.wxcacheviewer.domain.model.SortDirection
import top.aico.wxcacheviewer.domain.model.SortField
import top.aico.wxcacheviewer.domain.update.UpdateChannel
import java.io.File

enum class GridMode { TWO, ONE }

class SettingsRepository(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("wxcacheviewer", Context.MODE_PRIVATE)

    var selectedAccountHash: String
        get() = preferences.getString(KEY_SELECTED_ACCOUNT, "").orEmpty()
        set(value) {
            preferences.edit().putString(KEY_SELECTED_ACCOUNT, value).apply()
        }

    var gridMode: GridMode
        get() = if (preferences.getString(KEY_GRID_MODE, GridMode.TWO.name) == GridMode.ONE.name) {
            GridMode.ONE
        } else {
            GridMode.TWO
        }
        set(value) {
            preferences.edit().putString(KEY_GRID_MODE, value.name).apply()
        }

    var sortField: SortField
        get() = SortField.entries.firstOrNull {
            it.name == preferences.getString(KEY_SORT_FIELD, null)
        } ?: SortField.MODIFIED_TIME
        set(value) {
            preferences.edit().putString(KEY_SORT_FIELD, value.name).apply()
        }

    var sortDirection: SortDirection
        get() = SortDirection.entries.firstOrNull {
            it.name == preferences.getString(KEY_SORT_DIRECTION, null)
        } ?: SortDirection.DESC
        set(value) {
            preferences.edit().putString(KEY_SORT_DIRECTION, value.name).apply()
        }

    fun getRemark(hash: String): String = preferences.getString("$KEY_REMARK$hash", "").orEmpty()

    fun setRemark(hash: String, value: String) {
        preferences.edit().putString("$KEY_REMARK$hash", value).apply()
    }

    /** 播放器应用内音量（0f..1f），与系统媒体音量相互独立。 */
    var playbackVolume: Float
        get() = preferences.getFloat(KEY_PLAYBACK_VOLUME, 1f).coerceIn(0f, 1f)
        set(value) {
            preferences.edit().putFloat(KEY_PLAYBACK_VOLUME, value.coerceIn(0f, 1f)).apply()
        }

    /**
     * 导出根目录的绝对路径，空串表示还没选过。
     *
     * 导出时在这个目录下按类型再建子目录（视频 / 图片 / 语音 / 文件），
     * 不再散落到 Music、Movies、Download 几个系统目录里。
     */
    var exportRootPath: String
        get() = preferences.getString(KEY_EXPORT_ROOT_PATH, "").orEmpty()
        set(value) {
            preferences.edit().putString(KEY_EXPORT_ROOT_PATH, value).apply()
        }

    /** 保存目录选择的 SAF 授权 URI，换设备或清数据后失效时用来提示重新选择。 */
    var exportRootUri: String
        get() = preferences.getString(KEY_EXPORT_ROOT_URI, "").orEmpty()
        set(value) {
            preferences.edit().putString(KEY_EXPORT_ROOT_URI, value).apply()
        }

    /** 实际使用的导出根目录，未设置时落到 Download/WXCacheViewer。 */
    val exportRootDir: File
        get() = File(exportRootPath.ifBlank { defaultExportRootPath() })

    /** 检查更新时使用的渠道，默认只提示正式版。 */
    var updateChannel: UpdateChannel
        get() = UpdateChannel.entries.firstOrNull {
            it.name == preferences.getString(KEY_UPDATE_CHANNEL, null)
        } ?: UpdateChannel.STABLE
        set(value) {
            preferences.edit().putString(KEY_UPDATE_CHANNEL, value.name).apply()
        }

    @Suppress("DEPRECATION")
    fun defaultExportRootPath(): String = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        DEFAULT_EXPORT_DIR_NAME
    ).absolutePath

    private companion object {
        const val KEY_SELECTED_ACCOUNT = "selected_account_hash"
        const val KEY_GRID_MODE = "grid_mode"
        const val KEY_SORT_FIELD = "sort_field"
        const val KEY_SORT_DIRECTION = "sort_direction"
        const val KEY_REMARK = "remark_"
        const val KEY_PLAYBACK_VOLUME = "playback_volume"
        const val KEY_EXPORT_ROOT_PATH = "export_root_path"
        const val KEY_EXPORT_ROOT_URI = "export_root_uri"
        const val KEY_UPDATE_CHANNEL = "update_channel"
        const val DEFAULT_EXPORT_DIR_NAME = "WXCacheViewer"
    }
}
