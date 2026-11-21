package top.aico.wxcacheviewer.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import top.aico.wxcacheviewer.ui.account.AccountSelectScreen
import top.aico.wxcacheviewer.viewmodel.AccountViewModel

@Composable
fun AppRoot(
    selectedAccountHash: String,
    onSelectedAccountChanged: (String) -> Unit
) {
    val accountViewModel: AccountViewModel = viewModel()
    val accountState by accountViewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        accountViewModel.refresh()
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        if (selectedAccountHash.isEmpty()) {
            AccountSelectScreen(
                state = accountState,
                onSelect = {
                    accountViewModel.select(it)
                    onSelectedAccountChanged(it)
                },
                onSaveRemark = accountViewModel::saveRemark,
                onRetry = accountViewModel::refresh
            )
        } else {
            Text(text = "已选择账号：$selectedAccountHash")
        }
    }
}
