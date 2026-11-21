package top.aico.wxcacheviewer.viewmodel

import android.app.Application
import android.media.MediaScannerConnection
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.aico.wxcacheviewer.R
import top.aico.wxcacheviewer.data.repository.CacheRepository
import top.aico.wxcacheviewer.data.repository.GridMode
import top.aico.wxcacheviewer.data.repository.SettingsRepository
import top.aico.wxcacheviewer.data.root.RootCommandExecutor
import top.aico.wxcacheviewer.domain.CacheFileSorter
import top.aico.wxcacheviewer.domain.model.CacheFile
import top.aico.wxcacheviewer.domain.model.SortOption
import java.io.File

data class GalleryUiState(
    val loading: Boolean = true,
    val files: List<CacheFile> = emptyList(),
    val gridMode: GridMode = GridMode.TWO,
    val sortOption: SortOption = SortOption(),
    val refreshing: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val actionBusy: Boolean = false,
    /** 是否处于多选模式；全不选后仍保持该状态，只有显式退出或删除完成才结束。 */
    val selectionMode: Boolean = false,
    val selectedNames: Set<String> = emptySet(),
    /** 变化时列表回到顶部；用于排序、切号、刷新等显式操作，删除不触发。 */
    val resetScrollToken: Int = 0
) {
    val allSelected: Boolean
        get() = files.isNotEmpty() && selectedNames.size == files.size
}

class GalleryViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = SettingsRepository(application)
    private val repository = CacheRepository(RootCommandExecutor())
    private val _uiState = MutableStateFlow(
        GalleryUiState(
            gridMode = settings.gridMode,
            sortOption = SortOption(settings.sortField, settings.sortDirection)
        )
    )
    val uiState: StateFlow<GalleryUiState> = _uiState.asStateFlow()

    fun load(accountHash: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            try {
                val files = repository.listFiles(accountHash)
                _uiState.update {
                    it.withFiles(files)
                        .copy(loading = false, resetScrollToken = it.resetScrollToken + 1)
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(loading = false, error = e.message ?: "读取缓存文件失败") }
            }
        }
    }

    /**
     * 下拉刷新：保留当前列表内容，只显示刷新指示器，避免整屏回到加载态。
     */
    fun refresh(accountHash: String) {
        if (_uiState.value.refreshing) return
        viewModelScope.launch {
            _uiState.update { it.copy(refreshing = true, error = null) }
            try {
                val files = repository.listFiles(accountHash)
                _uiState.update {
                    it.withFiles(files)
                        .copy(refreshing = false, resetScrollToken = it.resetScrollToken + 1)
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(refreshing = false, error = e.message ?: "刷新缓存文件失败")
                }
            }
        }
    }

    fun setGridMode(mode: GridMode) {
        if (_uiState.value.gridMode == mode) return
        settings.gridMode = mode
        _uiState.update { it.copy(gridMode = mode) }
    }

    fun setSortOption(option: SortOption) {
        if (_uiState.value.sortOption == option) return
        settings.sortField = option.field
        settings.sortDirection = option.direction
        _uiState.update {
            it.copy(
                sortOption = option,
                files = CacheFileSorter.sort(it.files, option),
                resetScrollToken = it.resetScrollToken + 1
            )
        }
    }

    /** 长按进入多选模式，并把被长按的文件选中。 */
    fun startSelection(name: String) {
        _uiState.update {
            it.copy(selectionMode = true, selectedNames = it.selectedNames + name)
        }
    }

    fun toggleSelection(name: String) {
        _uiState.update { state ->
            if (!state.selectionMode) return@update state
            val next = if (name in state.selectedNames) {
                state.selectedNames - name
            } else {
                state.selectedNames + name
            }
            state.copy(selectedNames = next)
        }
    }

    /** 全选/全不选切换；全不选后仍停留在多选模式。 */
    fun toggleSelectAll() {
        _uiState.update { state ->
            if (!state.selectionMode) return@update state
            val next = if (state.allSelected) emptySet() else state.files.mapTo(LinkedHashSet()) { it.name }
            state.copy(selectedNames = next)
        }
    }

    fun invertSelection() {
        _uiState.update { state ->
            if (!state.selectionMode) return@update state
            val next = state.files.mapNotNullTo(LinkedHashSet()) {
                if (it.name in state.selectedNames) null else it.name
            }
            state.copy(selectedNames = next)
        }
    }

    fun exitSelection() {
        _uiState.update {
            if (!it.selectionMode) it else it.copy(selectionMode = false, selectedNames = emptySet())
        }
    }

    /** 批量导出到公共 Movies/WXCacheViewer 目录，并通知媒体库扫描，方便相册/播放器直接看到。 */
    @Suppress("DEPRECATION")
    fun exportSelected() {
        val current = _uiState.value
        if (current.actionBusy) return
        val targets = current.selectedFiles()
        if (targets.isEmpty()) return
        val app = getApplication<Application>()
        viewModelScope.launch {
            _uiState.update { it.copy(actionBusy = true) }
            val destinationDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                EXPORT_DIR_NAME
            )
            val exportedPaths = ArrayList<String>(targets.size)
            var failed = 0
            var lastError: String? = null
            targets.forEach { file ->
                try {
                    exportedPaths += repository.exportFile(file, destinationDir).absolutePath
                } catch (e: Exception) {
                    failed++
                    lastError = e.message
                }
            }
            if (exportedPaths.isNotEmpty()) {
                MediaScannerConnection.scanFile(
                    app,
                    exportedPaths.toTypedArray(),
                    Array(exportedPaths.size) { "video/mp4" },
                    null
                )
            }
            _uiState.update {
                it.copy(
                    actionBusy = false,
                    notice = when {
                        exportedPaths.isEmpty() ->
                            app.getString(R.string.export_failed, lastError.orEmpty())
                        failed == 0 -> app.getString(
                            R.string.export_selected_success,
                            exportedPaths.size,
                            EXPORT_DIR_NAME
                        )
                        else -> app.getString(
                            R.string.export_selected_partial,
                            exportedPaths.size,
                            failed
                        )
                    }
                )
            }
        }
    }

    /** 批量删除；全部成功后退出多选模式，有失败则保留失败项继续选中。 */
    fun deleteSelected() {
        val current = _uiState.value
        if (current.actionBusy) return
        val targets = current.selectedFiles()
        if (targets.isEmpty()) return
        val app = getApplication<Application>()
        viewModelScope.launch {
            _uiState.update { it.copy(actionBusy = true) }
            val deleted = HashSet<String>(targets.size)
            var failed = 0
            var lastError: String? = null
            targets.forEach { file ->
                try {
                    repository.deleteFile(file)
                    deleted += file.name
                } catch (e: Exception) {
                    failed++
                    lastError = e.message
                }
            }
            _uiState.update { state ->
                val remaining = state.files.filterNot { it.name in deleted }
                state.copy(
                    actionBusy = false,
                    files = remaining,
                    selectionMode = failed > 0 && remaining.isNotEmpty(),
                    selectedNames = if (failed > 0) {
                        state.selectedNames.filterTo(HashSet()) { it !in deleted }
                    } else {
                        emptySet()
                    },
                    notice = when {
                        deleted.isEmpty() -> app.getString(R.string.delete_failed, lastError.orEmpty())
                        failed == 0 -> app.getString(R.string.delete_selected_success, deleted.size)
                        else -> app.getString(
                            R.string.delete_selected_partial,
                            deleted.size,
                            failed
                        )
                    }
                )
            }
        }
    }

    fun consumeNotice() {
        _uiState.update { it.copy(notice = null) }
    }

    private fun GalleryUiState.selectedFiles(): List<CacheFile> =
        files.filter { it.name in selectedNames }

    /**
     * 换入新的文件列表：按当前排序规则重排，并把已不存在的文件从选中集合中剔除，
     * 避免刷新后残留无效选中项。
     */
    private fun GalleryUiState.withFiles(files: List<CacheFile>): GalleryUiState {
        val sorted = CacheFileSorter.sort(files, sortOption)
        val names = sorted.mapTo(HashSet()) { it.name }
        return copy(
            files = sorted,
            selectedNames = selectedNames.filterTo(HashSet()) { it in names },
            selectionMode = selectionMode && sorted.isNotEmpty()
        )
    }

    private companion object {
        const val EXPORT_DIR_NAME = "WXCacheViewer"
    }
}
