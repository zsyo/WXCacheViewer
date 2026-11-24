package top.aico.wxcacheviewer.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.aico.wxcacheviewer.data.log.AppLog
import top.aico.wxcacheviewer.data.repository.AccountNameRepository
import top.aico.wxcacheviewer.data.repository.CacheRepository
import top.aico.wxcacheviewer.data.repository.SettingsRepository
import top.aico.wxcacheviewer.data.root.RootCommandExecutor
import top.aico.wxcacheviewer.data.root.RootDiagnostics
import top.aico.wxcacheviewer.data.root.RootState
import top.aico.wxcacheviewer.domain.model.Account

data class AccountUiState(
    val loading: Boolean = true,
    val rootState: RootState? = null,
    val accounts: List<Account> = emptyList(),
    val remarks: Map<String, String> = emptyMap(),
    /** 自动识别的账号名称，手动备注为空时作为默认显示值。 */
    val autoNames: Map<String, String> = emptyMap(),
    val error: String? = null,
    val diagnostics: String? = null
)

class AccountViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = SettingsRepository(application)
    private val executor = RootCommandExecutor()
    private val repository = CacheRepository(executor)
    private val nameRepository = AccountNameRepository(application, executor)
    private val _uiState = MutableStateFlow(AccountUiState())
    val uiState: StateFlow<AccountUiState> = _uiState.asStateFlow()

    /** 防重入：首次进入与「重试」点击可能并发，旧响应会覆盖刚保存的备注和账号状态。 */
    private var refreshJob: Job? = null

    private var verifyJob: Job? = null

    /**
     * 回到前台时复检 root 权限。
     *
     * 用户可能是去 root 管理器撤销授权的：不检测的话应用仍停在相册里，之后任何操作都只是
     * 一句「读取失败」或空列表，看不到「去重新授权」的引导。检测到就切回引导页；
     * 如果权限又恢复了（用户重新允许），顺手重新拉账号，界面自己回到列表。
     */
    fun verifyRoot() {
        // 首屏加载或用户点了「重新检测」正在跑，已有结论，别并发覆盖
        if (refreshJob?.isActive == true || verifyJob?.isActive == true) return
        verifyJob = viewModelScope.launch {
            val root = try {
                executor.verifyRoot()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.log("account", "root verify failed: ${e.message}")
                return@launch
            }
            if (root is RootState.Unavailable) {
                AppLog.log("account", "root revoked: ${root.reason}")
                _uiState.update {
                    it.copy(rootState = root, accounts = emptyList(), error = null, diagnostics = null)
                }
            } else if (_uiState.value.rootState is RootState.Unavailable) {
                AppLog.log("account", "root restored, reload")
                refresh()
            }
        }
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null, diagnostics = null) }
            val root = try {
                repository.checkRoot()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        loading = false,
                        error = e.message ?: "检查 root 权限失败"
                    )
                }
                return@launch
            }
            AppLog.log("account", "rootState=$root")
            if (root is RootState.Unavailable) {
                // 先让引导页出来：su 被拒绝时每条探测命令都要等，攒完诊断再显示会让用户
                // 对着「正在读取账号」发呆十几秒。诊断随后补上，界面自己会刷新。
                _uiState.update { it.copy(loading = false, rootState = root) }
                // 没有 su 二进制时跑 shell 探测毫无信息量，只留设备环境；
                // 授权超时同理，否则又是一轮等待。被拒绝或原因不明时才有必要看 su 到底回了什么。
                val privileged = root.reason == RootState.Reason.DENIED ||
                    root.reason == RootState.Reason.UNKNOWN
                val diagnostics = RootDiagnostics.collect(getApplication(), executor, privileged)
                _uiState.update { it.copy(diagnostics = diagnostics) }
                return@launch
            }
            try {
                val accounts = repository.discoverAccounts()
                AppLog.log("account", "discovered=${accounts.size} ${accounts.map { it.hash to it.hasVideoCache }}")
                val remarks = accounts.associate { it.hash to settings.getRemark(it.hash) }
                _uiState.update {
                    it.copy(
                        loading = false,
                        rootState = root,
                        accounts = accounts,
                        remarks = remarks,
                        autoNames = emptyMap()
                    )
                }
                if (accounts.isEmpty()) {
                    val diagnostics = RootDiagnostics.collect(getApplication(), executor)
                    _uiState.update { it.copy(diagnostics = diagnostics) }
                    return@launch
                }
                // 昵称解析要逐个账号复制数据库，先让列表出来，识别到再回填，避免卡住首屏。
                val autoNames = nameRepository.resolveNames(accounts)
                _uiState.update { it.copy(autoNames = autoNames) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 被新的 refresh 取消，直接退出，不能覆盖新一次的状态
                throw e
            } catch (e: Exception) {
                val diagnostics = RootDiagnostics.collect(getApplication(), executor)
                _uiState.update {
                    it.copy(
                        loading = false,
                        rootState = root,
                        error = e.message ?: "读取账号失败",
                        diagnostics = diagnostics
                    )
                }
            }
        }
    }

    fun saveRemark(hash: String, value: String) {
        settings.setRemark(hash, value)
        _uiState.update { it.copy(remarks = it.remarks + (hash to value)) }
    }

    fun select(hash: String) {
        settings.selectedAccountHash = hash
    }
}
