package top.aico.wxcacheviewer.data.repository

import android.content.Context

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

    fun getRemark(hash: String): String = preferences.getString("$KEY_REMARK$hash", "").orEmpty()

    fun setRemark(hash: String, value: String) {
        preferences.edit().putString("$KEY_REMARK$hash", value).apply()
    }

    private companion object {
        const val KEY_SELECTED_ACCOUNT = "selected_account_hash"
        const val KEY_GRID_MODE = "grid_mode"
        const val KEY_REMARK = "remark_"
    }
}
