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
import top.aico.wxcacheviewer.data.repository.SettingsRepository
import top.aico.wxcacheviewer.data.root.RootCommandExecutor
import top.aico.wxcacheviewer.data.root.RootState
import top.aico.wxcacheviewer.domain.model.Account

data class AccountUiState(
    val loading: Boolean = true,
    val rootState: RootState? = null,
    val accounts: List<Account> = emptyList(),
    val remarks: Map<String, String> = emptyMap(),
    val error: String? = null
)

class AccountViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = SettingsRepository(application)
    private val repository = CacheRepository(RootCommandExecutor())
    private val _uiState = MutableStateFlow(AccountUiState())
    val uiState: StateFlow<AccountUiState> = _uiState.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            val root = repository.checkRoot()
            if (root is RootState.Unavailable) {
                _uiState.update { it.copy(loading = false, rootState = root) }
                return@launch
            }
            try {
                val accounts = repository.discoverAccounts()
                val remarks = accounts.associate { it.hash to settings.getRemark(it.hash) }
                _uiState.update {
                    it.copy(loading = false, rootState = root, accounts = accounts, remarks = remarks)
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(loading = false, rootState = root, error = e.message ?: "读取账号失败") }
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
