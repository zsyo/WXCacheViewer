package top.aico.wxcacheviewer.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.aico.wxcacheviewer.R
import top.aico.wxcacheviewer.data.repository.SettingsRepository
import top.aico.wxcacheviewer.data.update.UpdateCheckException
import top.aico.wxcacheviewer.data.update.UpdateChecker
import top.aico.wxcacheviewer.data.update.UpdateError
import top.aico.wxcacheviewer.domain.update.ReleaseInfo
import top.aico.wxcacheviewer.domain.update.SemanticVersion
import top.aico.wxcacheviewer.domain.update.UpdateChannel
import top.aico.wxcacheviewer.ui.common.AppInfo
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
    val updateChannel: UpdateChannel = UpdateChannel.STABLE,
    val checkingUpdate: Boolean = false,
    /** 手动检查发现新版本时非空，界面据此弹窗；关闭后置空。 */
    val updateRelease: ReleaseInfo? = null,
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

    /** 检查更新只有一个 GET，构造时不会发起任何请求。 */
    private val updateChecker = UpdateChecker(
        userAgent = "${UpdateChecker.DEFAULT_USER_AGENT}/${AppInfo.versionName(application)}"
    )

    private val _uiState = MutableStateFlow(
        SettingsUiState(
            exportRootPath = settings.exportRootDir.absolutePath,
            exportRootCustom = settings.exportRootPath.isNotBlank(),
            updateChannel = settings.updateChannel
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

    fun setUpdateChannel(channel: UpdateChannel) {
        if (_uiState.value.updateChannel == channel) return
        settings.updateChannel = channel
        _uiState.update { it.copy(updateChannel = channel) }
    }

    /**
     * 手动检查更新。整个应用只有这一个入口会联网，启动、切页、回到前台都不会触发。
     */
    fun checkForUpdate() {
        if (_uiState.value.checkingUpdate) return
        val channel = _uiState.value.updateChannel
        _uiState.update { it.copy(checkingUpdate = true, notice = null) }
        viewModelScope.launch {
            val app = getApplication<Application>()
            val outcome = withContext(Dispatchers.IO) {
                try {
                    Result.success(updateChecker.fetchLatest(channel))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            val currentName = AppInfo.versionName(app)
            val current = SemanticVersion.parse(currentName)
            _uiState.update { state ->
                val failure = outcome.exceptionOrNull()
                if (failure != null) {
                    return@update state.copy(
                        checkingUpdate = false,
                        notice = updateFailureMessage(failure)
                    )
                }
                val release = outcome.getOrNull()
                when {
                    release == null -> state.copy(
                        checkingUpdate = false,
                        notice = app.getString(R.string.settings_update_no_release)
                    )
                    // 版本号解析不出来（手动构建的 dev-xxxx）时不比较，直接给出最新版本
                    current != null && release.version <= current -> state.copy(
                        checkingUpdate = false,
                        notice = app.getString(R.string.settings_update_up_to_date, currentName)
                    )
                    else -> state.copy(checkingUpdate = false, updateRelease = release)
                }
            }
        }
    }

    fun consumeUpdateRelease() {
        _uiState.update { it.copy(updateRelease = null) }
    }

    fun consumeNotice() {
        _uiState.update { it.copy(notice = null) }
    }

    private fun updateFailureMessage(failure: Throwable): String {
        val app = getApplication<Application>()
        val cause = failure as? UpdateCheckException
        return when (cause?.error) {
            UpdateError.NETWORK, null -> app.getString(R.string.settings_update_failed_network)
            UpdateError.RATE_LIMIT -> app.getString(R.string.settings_update_failed_rate_limit)
            UpdateError.HTTP -> app.getString(
                R.string.settings_update_failed_http,
                cause.detail.orEmpty()
            )
            UpdateError.PARSE -> app.getString(R.string.settings_update_failed_parse)
        }
    }

    private fun sizeOf(dir: File): Long =
        if (!dir.exists()) 0L else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
