package top.aico.wxcacheviewer.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.aico.wxcacheviewer.R
import top.aico.wxcacheviewer.data.repository.SettingsRepository
import top.aico.wxcacheviewer.ui.common.formatFileSize
import java.io.File

data class SettingsUiState(
    /** 导出根目录的绝对路径，未自定义时是 Download/WXCacheViewer。 */
    val exportRootPath: String = "",
    /** 是否是用户自己选的目录；false 表示还在用默认目录。 */
    val exportRootCustom: Boolean = false,
    val cacheBytes: Long = 0L,
    val cacheScanning: Boolean = true,
    val cacheClearing: Boolean = false,
    val notice: String? = null
)

/**
 * 设置页的状态：导出目录与临时缓存。
 *
 * 缓存只统计应用私有的 `cacheDir`（复制出来的媒体、语音解码产物都在这里），
 * 清理也只删它，不碰微信的缓存目录，删错了最多是下次重新复制一遍。
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = SettingsRepository(application)
    private val cacheDir: File
        get() = getApplication<Application>().cacheDir

    private val _uiState = MutableStateFlow(
        SettingsUiState(
            exportRootPath = settings.exportRootDir.absolutePath,
            exportRootCustom = settings.exportRootPath.isNotBlank()
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    /** 扫描代次：被新一次扫描取代的旧结果直接丢弃，不写回状态。 */
    private var scanGeneration = 0

    init {
        refreshCacheSize()
    }

    fun refreshCacheSize() {
        val generation = ++scanGeneration
        _uiState.update { it.copy(cacheScanning = true) }
        viewModelScope.launch {
            val size = withContext(Dispatchers.IO) { sizeOf(cacheDir) }
            if (generation != scanGeneration) return@launch
            _uiState.update { it.copy(cacheBytes = size, cacheScanning = false) }
        }
    }

    /** 记录用户选择的目录；path 已由 [top.aico.wxcacheviewer.domain.ExportLocation] 解析成绝对路径。 */
    fun setExportRoot(path: String, uri: String) {
        settings.exportRootPath = path
        settings.exportRootUri = uri
        _uiState.update {
            it.copy(
                exportRootPath = path,
                exportRootCustom = true,
                notice = getApplication<Application>().getString(R.string.settings_export_root_changed)
            )
        }
    }

    fun resetExportRoot() {
        settings.exportRootPath = ""
        settings.exportRootUri = ""
        _uiState.update {
            it.copy(
                exportRootPath = settings.defaultExportRootPath(),
                exportRootCustom = false,
                notice = getApplication<Application>().getString(R.string.settings_export_root_reset)
            )
        }
    }

    fun clearCache() {
        if (_uiState.value.cacheClearing) return
        // 清理会改变缓存大小，作废旧扫描，避免它晚返回时覆盖清理后的结果
        scanGeneration++
        _uiState.update { it.copy(cacheClearing = true) }
        viewModelScope.launch {
            val (freed, remaining) = withContext(Dispatchers.IO) {
                val before = sizeOf(cacheDir)
                cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                val after = sizeOf(cacheDir)
                before - after to after
            }
            val app = getApplication<Application>()
            _uiState.update {
                it.copy(
                    cacheBytes = remaining,
                    cacheScanning = false,
                    cacheClearing = false,
                    notice = app.getString(R.string.settings_cache_cleared, formatFileSize(freed))
                )
            }
        }
    }

    fun consumeNotice() {
        _uiState.update { it.copy(notice = null) }
    }

    private fun sizeOf(dir: File): Long =
        if (!dir.exists()) 0L else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
