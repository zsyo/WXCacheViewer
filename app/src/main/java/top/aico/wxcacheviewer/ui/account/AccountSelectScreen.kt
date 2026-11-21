package top.aico.wxcacheviewer.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.aico.wxcacheviewer.R
import top.aico.wxcacheviewer.data.root.RootState
import top.aico.wxcacheviewer.ui.common.ErrorView
import top.aico.wxcacheviewer.ui.common.LoadingView
import top.aico.wxcacheviewer.viewmodel.AccountUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSelectScreen(
    state: AccountUiState,
    onSelect: (String) -> Unit,
    onSaveRemark: (String, String) -> Unit,
    onRetry: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(text = stringResource(id = R.string.select_account_title)) })
        }
    ) { padding ->
        when {
            state.rootState is RootState.Unavailable -> ErrorView(
                message = rootErrorMessage(state.rootState.reason),
                onRetry = onRetry,
                details = state.diagnostics
            )

            state.loading -> LoadingView(text = stringResource(id = R.string.loading))

            state.error != null -> ErrorView(
                message = state.error,
                onRetry = onRetry,
                details = state.diagnostics
            )

            state.accounts.isEmpty() -> ErrorView(
                message = stringResource(id = R.string.no_account_found),
                onRetry = onRetry,
                details = state.diagnostics
            )

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(items = state.accounts, key = { it.hash }) { account ->
                    AccountItem(
                        hash = account.hash,
                        remark = state.remarks[account.hash].orEmpty(),
                        hasVideoCache = account.hasVideoCache,
                        onSaveRemark = { onSaveRemark(account.hash, it) },
                        onSelect = { onSelect(account.hash) }
                    )
                }
                item {
                    if (state.accounts.size == 1) {
                        Text(
                            text = stringResource(id = R.string.single_account_hint),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountItem(
    hash: String,
    remark: String,
    hasVideoCache: Boolean,
    onSaveRemark: (String) -> Unit,
    onSelect: () -> Unit
) {
    var remarkText by remember(hash) { mutableStateOf(remark) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = hash, style = MaterialTheme.typography.bodyMedium)
            if (hasVideoCache) {
                Text(
                    text = stringResource(id = R.string.account_has_video_cache),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            OutlinedTextField(
                value = remarkText,
                onValueChange = {
                    remarkText = it
                    onSaveRemark(it)
                },
                label = { Text(text = stringResource(id = R.string.remark_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = onSelect, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(id = R.string.use_account))
            }
        }
    }
}

@Composable
private fun rootErrorMessage(reason: RootState.Reason): String = when (reason) {
    RootState.Reason.NO_SU -> stringResource(id = R.string.root_error_no_su)
    RootState.Reason.DENIED -> stringResource(id = R.string.root_error_denied)
    RootState.Reason.TIMEOUT -> stringResource(id = R.string.root_error_timeout)
    RootState.Reason.UNKNOWN -> stringResource(id = R.string.root_error_unknown)
}
