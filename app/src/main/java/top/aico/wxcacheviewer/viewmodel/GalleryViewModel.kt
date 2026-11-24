package top.aico.wxcacheviewer.viewmodel

import android.app.Application
import android.media.MediaScannerConnection
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.aico.wxcacheviewer.R
import top.aico.wxcacheviewer.data.audio.VoicePreparer
import top.aico.wxcacheviewer.data.emoji.EmojiKeyStore
import top.aico.wxcacheviewer.data.emoji.EmojiPreparer
import top.aico.wxcacheviewer.data.repository.CacheRepository
import top.aico.wxcacheviewer.data.repository.GridMode
import top.aico.wxcacheviewer.data.repository.PageCursor
import top.aico.wxcacheviewer.data.repository.SettingsRepository
import top.aico.wxcacheviewer.data.root.RootCommandExecutor
import top.aico.wxcacheviewer.domain.CacheFileSorter
import top.aico.wxcacheviewer.domain.ExportCategory
import top.aico.wxcacheviewer.domain.model.CacheFile
import top.aico.wxcacheviewer.domain.model.FileType
import top.aico.wxcacheviewer.domain.model.SortOption
import top.aico.wxcacheviewer.domain.model.mime
import top.aico.wxcacheviewer.domain.source.ExportKind
import top.aico.wxcacheviewer.domain.source.SourceRegistry
import top.aico.wxcacheviewer.domain.source.SourceTab
import java.io.File

/** 批量操作的种类，用于界面进度提示文案。 */
enum class BatchAction { EXPORT, DELETE }

/** 批量操作进度：done 为已完成数量，total 为总数。 */
data class BatchProgress(
    val action: BatchAction,
    val done: Int,
    val total: Int
)

data class GalleryUiState(
    val tab: SourceTab = SourceTab.FINDER,
    val loading: Boolean = true,
    val files: List<CacheFile> = emptyList(),
    /** 还有没取完的页；底部据此显示加载中或「加载更多」。 */
    val hasMore: Boolean = false,
    /** 正在取下一页，避免重复触发。 */
    val loadingMore: Boolean = false,
    val gridMode: GridMode = GridMode.TWO,
    val sortOption: SortOption = SortOption(),
    val refreshing: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    /** 正在执行的批量操作进度；为空表示空闲。 */
    val batchProgress: BatchProgress? = null,
    /** 是否处于多选模式；全不选后仍保持该状态，只有显式退出或删除完成才结束。 */
    val selectionMode: Boolean = false,
    /** 已选文件的唯一 key（`源:相对路径`），跨目录同名文件不会互相覆盖。 */
    val selectedKeys: Set<String> = emptySet(),
    /** 变化时列表回到顶部；用于排序、切号、刷新等显式操作，删除不触发。 */
    val resetScrollToken: Int = 0
) {
    val allSelected: Boolean
        get() = files.isNotEmpty() && selectedKeys.size == files.size

    val actionBusy: Boolean
        get() = batchProgress != null

    /** 聊天图片、朋友圈等只读源不提供删除。 */
    val canDelete: Boolean
        get() = tab.allowsDelete
}

class GalleryViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = SettingsRepository(application)

    /** 表情解密密钥：由最后登录账号的 uin 推导，且要校验属于当前账号目录。 */
    private val emojiKeys = EmojiKeyStore(RootCommandExecutor())
    private val repository = CacheRepository(RootCommandExecutor(), emojiKeys)

    /** 语音要先解码再导出，导出逻辑与其它缓存源不同。 */
    private val voicePreparer by lazy { VoicePreparer(application, RootCommandExecutor()) }

    /** 加密表情导出前要先解开头部，否则导出的是一份打不开的文件。 */
    private val emojiPreparer by lazy {
        EmojiPreparer(application, RootCommandExecutor(), emojiKeys)
    }
    private val _uiState = MutableStateFlow(
        GalleryUiState(
            gridMode = settings.gridMode,
            sortOption = SortOption(settings.sortField, settings.sortDirection)
        )
    )
    val uiState: StateFlow<GalleryUiState> = _uiState.asStateFlow()

    /** 下一页的游标；null 表示没有更多。 */
    private var cursor: PageCursor? = null

    /**
     * 加载代次。每次 load/refresh 递增；在飞的 loadMore 返回时若代次已变就丢弃结果。
     * 否则「下拉刷新 + 旧 loadMore 并发」会让旧页数据混进新列表、游标错乱、重复或跳页。
     */
    private var loadGeneration = 0

    fun load(accountHash: String, tab: SourceTab) {
        val generation = ++loadGeneration
        viewModelScope.launch {
            repository.invalidate()
            cursor = null
            _uiState.update {
                it.copy(
                    tab = tab,
                    loading = true,
                    error = null,
                    files = emptyList(),
                    hasMore = false,
                    loadingMore = false,
                    selectionMode = false,
                    selectedKeys = emptySet()
                )
            }
            try {
                val page = repository.loadPage(accountHash, tab.sources, null)
                if (generation != loadGeneration) return@launch
                cursor = page.nextCursor
                _uiState.update {
                    it.copy(
                        files = sortPage(page.files, it.sortOption),
                        loading = false,
                        hasMore = page.nextCursor != null,
                        error = page.error,
                        resetScrollToken = it.resetScrollToken + 1
                    )
                }
            } catch (e: Exception) {
                if (generation != loadGeneration) return@launch
                _uiState.update { it.copy(loading = false, error = e.message ?: "读取缓存文件失败") }
            }
        }
    }

    /**
     * 取下一页并追加到列表末尾。滚动快到底部时调用，重复调用会被 [GalleryUiState.loadingMore] 挡掉。
     */
    fun loadMore(accountHash: String) {
        val state = _uiState.value
        val next = cursor
        if (state.loading || state.loadingMore || state.refreshing || next == null) return
        val generation = loadGeneration
        viewModelScope.launch {
            _uiState.update { it.copy(loadingMore = true) }
            try {
                val page = repository.loadPage(accountHash, state.tab.sources, next)
                if (generation != loadGeneration) return@launch
                cursor = page.nextCursor
                _uiState.update { current ->
                    val merged = current.files + sortPage(page.files, current.sortOption)
                    current.copy(
                        files = merged,
                        loadingMore = false,
                        hasMore = page.nextCursor != null,
                        error = page.error
                    )
                }
            } catch (e: Exception) {
                if (generation != loadGeneration) return@launch
                _uiState.update {
                    it.copy(loadingMore = false, error = e.message ?: "读取缓存文件失败")
                }
            }
        }
    }

    /**
     * 下拉刷新：保留当前列表内容，只显示刷新指示器，避免整屏回到加载态。
     */
    fun refresh(accountHash: String) {
        if (_uiState.value.refreshing) return
        // 递增代次使在飞的 loadMore 失效；它返回时会自行丢弃结果。
        val generation = ++loadGeneration
        val tab = _uiState.value.tab
        viewModelScope.launch {
            _uiState.update {
                it.copy(refreshing = true, loadingMore = false, error = null)
            }
            repository.invalidate()
            cursor = null
            try {
                val page = repository.loadPage(accountHash, tab.sources, null)
                if (generation != loadGeneration) return@launch
                cursor = page.nextCursor
                _uiState.update {
                    it.copy(
                        files = sortPage(page.files, it.sortOption),
                        refreshing = false,
                        hasMore = page.nextCursor != null,
                        error = page.error,
                        resetScrollToken = it.resetScrollToken + 1
                    )
                }
            } catch (e: Exception) {
                if (generation != loadGeneration) return@launch
                _uiState.update {
                    it.copy(refreshing = false, error = e.message ?: "刷新缓存文件失败")
                }
            }
        }
    }

    /** 每页单独排序：分页加载时不能对全部已加载项重排，否则新页会把列表顶得乱跳。 */
    private fun sortPage(files: List<CacheFile>, option: SortOption): List<CacheFile> =
        CacheFileSorter.sort(files, option)

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
    fun startSelection(key: String) {
        _uiState.update {
            it.copy(selectionMode = true, selectedKeys = it.selectedKeys + key)
        }
    }

    fun toggleSelection(key: String) {
        _uiState.update { state ->
            if (!state.selectionMode) return@update state
            val next = if (key in state.selectedKeys) {
                state.selectedKeys - key
            } else {
                state.selectedKeys + key
            }
            state.copy(selectedKeys = next)
        }
    }

    /** 全选/全不选切换；全不选后仍停留在多选模式。 */
    fun toggleSelectAll() {
        _uiState.update { state ->
            if (!state.selectionMode) return@update state
            val next = if (state.allSelected) emptySet() else state.files.mapTo(LinkedHashSet()) { it.key }
            state.copy(selectedKeys = next)
        }
    }

    fun invertSelection() {
        _uiState.update { state ->
            if (!state.selectionMode) return@update state
            val next = state.files.mapNotNullTo(LinkedHashSet()) {
                if (it.key in state.selectedKeys) null else it.key
            }
            state.copy(selectedKeys = next)
        }
    }

    fun exitSelection() {
        _uiState.update {
            if (!it.selectionMode) it else it.copy(selectionMode = false, selectedKeys = emptySet())
        }
    }

    /**
     * 批量导出：视频进 Movies/WXCacheViewer，图片与表情进 Pictures/WXCacheViewer，
     * 完成后按每个文件各自的 MIME 通知媒体库扫描。
     */
    @Suppress("DEPRECATION")
    fun exportSelected() {
        val current = _uiState.value
        if (current.actionBusy) return
        val targets = current.selectedFiles()
        if (targets.isEmpty()) return
        val app = getApplication<Application>()
        viewModelScope.launch {
            _uiState.update {
                it.copy(batchProgress = BatchProgress(BatchAction.EXPORT, 0, targets.size))
            }
            val paths = ArrayList<String>(targets.size)
            val mimes = ArrayList<String>(targets.size)
            val dirLabels = LinkedHashSet<String>()
            var failed = 0
            var lastError: String? = null
            var done = 0
            targets.forEach { file ->
                try {
                    val target = exportTarget(file)
                    dirLabels += target.label
                    val exported = exportSingle(file, target.directory)
                    paths += exported.absolutePath
                    mimes += mimeOf(file, exported)
                } catch (e: Exception) {
                    failed++
                    lastError = e.message
                }
                done++
                publishProgress(BatchAction.EXPORT, done, targets.size)
            }
            if (paths.isNotEmpty()) {
                MediaScannerConnection.scanFile(
                    app,
                    paths.toTypedArray(),
                    mimes.toTypedArray(),
                    null
                )
            }
            _uiState.update {
                it.copy(
                    batchProgress = null,
                    notice = when {
                        paths.isEmpty() ->
                            app.getString(R.string.export_failed, lastError.orEmpty())
                        failed == 0 -> app.getString(
                            R.string.export_selected_success,
                            paths.size,
                            dirLabels.joinToString(" / ")
                        )
                        else -> app.getString(
                            R.string.export_selected_partial,
                            paths.size,
                            failed
                        )
                    }
                )
            }
        }
    }

    /** 单文件导出，供图片预览页的保存按钮使用。 */
    fun exportOne(file: CacheFile, onResult: (success: Boolean, message: String) -> Unit = { _, _ -> }) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            try {
                val target = exportTarget(file)
                val exported = exportSingle(file, target.directory)
                MediaScannerConnection.scanFile(
                    app,
                    arrayOf(exported.absolutePath),
                    arrayOf(mimeOf(file, exported)),
                    null
                )
                onResult(true, app.getString(R.string.export_one_success, exported.name))
            } catch (e: Exception) {
                onResult(false, app.getString(R.string.export_failed, e.message.orEmpty()))
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
            _uiState.update {
                it.copy(batchProgress = BatchProgress(BatchAction.DELETE, 0, targets.size))
            }
            val deleted = HashSet<String>(targets.size)
            var failed = 0
            var lastError: String? = null
            var done = 0
            targets.forEach { file ->
                try {
                    repository.deleteFile(file)
                    deleted += file.key
                } catch (e: Exception) {
                    failed++
                    lastError = e.message
                }
                done++
                publishProgress(BatchAction.DELETE, done, targets.size)
            }
            _uiState.update { state ->
                val remaining = state.files.filterNot { it.key in deleted }
                state.copy(
                    batchProgress = null,
                    files = remaining,
                    selectionMode = failed > 0 && remaining.isNotEmpty(),
                    selectedKeys = if (failed > 0) {
                        state.selectedKeys.filterTo(HashSet()) { it !in deleted }
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

    /** 每完成一项就刷新进度；若状态已被清理（例如界面重建）则忽略。 */
    private fun publishProgress(action: BatchAction, done: Int, total: Int) {
        _uiState.update { state ->
            if (state.batchProgress?.action != action) {
                state
            } else {
                state.copy(batchProgress = BatchProgress(action, done, total))
            }
        }
    }

    private fun GalleryUiState.selectedFiles(): List<CacheFile> =
        files.filter { it.key in selectedKeys }

    private data class ExportTarget(val directory: File, val label: String)

    /** 语音要先转码、表情要先解密（wxgf 还要解出首帧），其余缓存源直接 root 复制。 */
    private suspend fun exportSingle(file: CacheFile, directory: File): File = when {
        SourceRegistry.byId(file.sourceId).exportKind == ExportKind.MUSIC ->
            voicePreparer.export(file, directory)
        file.encrypted || file.type == FileType.WXGF -> emojiPreparer.export(file, directory)
        else -> repository.exportFile(file, directory)
    }

    /** 语音导出成 mp3（真 AMR 则原样导出），MIME 要跟着实际扩展名走。 */
    private fun mimeOf(file: CacheFile, exported: File): String = when {
        SourceRegistry.byId(file.sourceId).exportKind != ExportKind.MUSIC -> file.type.mime
        exported.extension.equals("amr", ignoreCase = true) -> "audio/amr"
        else -> "audio/mpeg"
    }

    /**
     * 导出目录：在设置页选定的根目录下按类型建子目录。
     * 没选过时根目录是 Download/WXCacheViewer，不再分散到 Music、Movies 等系统目录。
     */
    private fun exportTarget(file: CacheFile): ExportTarget {
        val category = when (SourceRegistry.byId(file.sourceId).exportKind) {
            ExportKind.MOVIES -> ExportCategory.VIDEO
            ExportKind.PICTURES -> ExportCategory.IMAGE
            ExportKind.MUSIC -> ExportCategory.VOICE
            ExportKind.DOWNLOADS -> ExportCategory.FILE
        }
        val root = settings.exportRootDir
        return ExportTarget(
            directory = File(root, category.directoryName),
            label = "${root.name}/${category.directoryName}"
        )
    }
}
