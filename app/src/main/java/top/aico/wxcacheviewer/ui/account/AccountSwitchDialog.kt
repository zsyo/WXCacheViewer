package top.aico.wxcacheviewer.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.aico.wxcacheviewer.R
import top.aico.wxcacheviewer.domain.model.Account

@Composable
fun AccountSwitchDialog(
    accounts: List<Account>,
    remarks: Map<String, String>,
    autoNames: Map<String, String>,
    currentHash: String,
    onSelect: (String) -> Unit,
    onSaveRemark: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(id = R.string.switch_account)) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(items = accounts, key = { it.hash }) { account ->
                    SwitchAccountItem(
                        hash = account.hash,
                        remark = remarks[account.hash].orEmpty(),
                        autoName = autoNames[account.hash].orEmpty(),
                        selected = account.hash == currentHash,
                        onSaveRemark = { onSaveRemark(account.hash, it) },
                        onSelect = { onSelect(account.hash) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(id = R.string.cancel))
            }
        }
    )
}

@Composable
private fun SwitchAccountItem(
    hash: String,
    remark: String,
    autoName: String,
    selected: Boolean,
    onSaveRemark: (String) -> Unit,
    onSelect: () -> Unit
) {
    var remarkText by remember(hash) { mutableStateOf(remark) }
    val displayName = remark.ifBlank { autoName }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = displayName.ifBlank { hash } + if (selected) {
                stringResource(id = R.string.account_current_suffix)
            } else {
                ""
            },
            style = MaterialTheme.typography.bodyMedium
        )
        if (displayName.isNotBlank()) {
            Text(
                text = hash,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        OutlinedTextField(
            value = remarkText,
            onValueChange = {
                remarkText = it
                onSaveRemark(it)
            },
            label = { Text(text = stringResource(id = R.string.remark_label)) },
            placeholder = if (autoName.isNotBlank()) {
                { Text(text = autoName) }
            } else {
                null
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
        )
        TextButton(onClick = onSelect) {
            Text(text = stringResource(id = R.string.use_account))
        }
    }
}
