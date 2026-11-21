package top.aico.wxcacheviewer.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.aico.wxcacheviewer.data.log.AppLog
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
    val error: String? = null,
    val diagnostics: String? = null
)

class AccountViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = SettingsRepository(application)
    private val executor = RootCommandExecutor()
    private val repository = CacheRepository(executor)
    private val _uiState = MutableStateFlow(AccountUiState())
    val uiState: StateFlow<AccountUiState> = _uiState.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null, diagnostics = null) }
            val root = repository.checkRoot()
            AppLog.log("account", "rootState=$root")
            if (root is RootState.Unavailable) {
                val diagnostics = RootDiagnostics.collect(getApplication(), executor)
                _uiState.update {
                    it.copy(loading = false, rootState = root, diagnostics = diagnostics)
                }
                return@launch
            }
            try {
                val accounts = repository.discoverAccounts()
                AppLog.log("account", "discovered=${accounts.size} ${accounts.map { it.hash to it.hasVideoCache }}")
                val remarks = accounts.associate { it.hash to settings.getRemark(it.hash) }
                _uiState.update {
                    it.copy(loading = false, rootState = root, accounts = accounts, remarks = remarks)
                }
                if (accounts.isEmpty()) {
                    val diagnostics = RootDiagnostics.collect(getApplication(), executor)
                    _uiState.update { it.copy(diagnostics = diagnostics) }
                }
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
