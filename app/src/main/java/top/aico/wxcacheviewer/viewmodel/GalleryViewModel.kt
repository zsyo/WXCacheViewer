package top.aico.wxcacheviewer.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.aico.wxcacheviewer.data.repository.CacheRepository
import top.aico.wxcacheviewer.data.repository.GridMode
import top.aico.wxcacheviewer.data.repository.SettingsRepository
import top.aico.wxcacheviewer.data.root.RootCommandExecutor
import top.aico.wxcacheviewer.domain.CacheFileSorter
import top.aico.wxcacheviewer.domain.model.CacheFile
import top.aico.wxcacheviewer.domain.model.SortOption

data class GalleryUiState(
    val loading: Boolean = true,
    val files: List<CacheFile> = emptyList(),
    val gridMode: GridMode = GridMode.TWO,
    val sortOption: SortOption = SortOption(),
    val error: String? = null
)

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
                    it.copy(
                        loading = false,
                        files = CacheFileSorter.sort(files, it.sortOption)
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(loading = false, error = e.message ?: "读取缓存文件失败") }
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
                files = CacheFileSorter.sort(it.files, option)
            )
        }
    }
}
