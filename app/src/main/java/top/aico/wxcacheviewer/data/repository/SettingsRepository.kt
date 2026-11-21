package top.aico.wxcacheviewer.data.repository

import android.content.Context
import top.aico.wxcacheviewer.domain.model.SortDirection
import top.aico.wxcacheviewer.domain.model.SortField

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

    private companion object {
        const val KEY_SELECTED_ACCOUNT = "selected_account_hash"
        const val KEY_GRID_MODE = "grid_mode"
        const val KEY_SORT_FIELD = "sort_field"
        const val KEY_SORT_DIRECTION = "sort_direction"
        const val KEY_REMARK = "remark_"
        const val KEY_PLAYBACK_VOLUME = "playback_volume"
    }
}
