package top.aico.wxcacheviewer.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.aico.wxcacheviewer.R
import top.aico.wxcacheviewer.data.repository.GridMode
import top.aico.wxcacheviewer.domain.model.CacheFile
import top.aico.wxcacheviewer.ui.common.ErrorView
import top.aico.wxcacheviewer.ui.common.LoadingView
import top.aico.wxcacheviewer.ui.common.formatFileSize
import top.aico.wxcacheviewer.viewmodel.GalleryUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    state: GalleryUiState,
    title: String,
    canSwitchAccount: Boolean,
    onToggleGridMode: () -> Unit,
    onRequestSwitch: () -> Unit,
    onRetry: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = title) },
                actions = {
                    TextButton(onClick = onToggleGridMode) {
                        Text(
                            text = if (state.gridMode == GridMode.TWO) {
                                stringResource(id = R.string.switch_to_one_column)
                            } else {
                                stringResource(id = R.string.switch_to_two_columns)
                            }
                        )
                    }
                    TextButton(onClick = onRequestSwitch, enabled = canSwitchAccount) {
                        Text(text = stringResource(id = R.string.switch_account))
                    }
                }
            )
        }
    ) { padding ->
        when {
            state.loading -> LoadingView(text = stringResource(id = R.string.loading_files))

            state.error != null -> ErrorView(message = state.error, onRetry = onRetry)

            state.files.isEmpty() -> ErrorView(
                message = stringResource(id = R.string.no_cache_files),
                onRetry = onRetry
            )

            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(if (state.gridMode == GridMode.TWO) 2 else 1),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items = state.files, key = { it.name }) { file ->
                    CacheFileTile(file = file)
                }
            }
        }
    }
}

@Composable
private fun CacheFileTile(file: CacheFile) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = file.type.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${file.type.name} / ${formatFileSize(file.size)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
