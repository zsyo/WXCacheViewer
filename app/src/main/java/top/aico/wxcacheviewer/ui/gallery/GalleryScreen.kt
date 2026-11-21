package top.aico.wxcacheviewer.ui.gallery

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.aico.wxcacheviewer.R
import top.aico.wxcacheviewer.data.repository.GridMode
import top.aico.wxcacheviewer.data.root.RootCommandExecutor
import top.aico.wxcacheviewer.data.thumbnail.ThumbnailLoader
import top.aico.wxcacheviewer.domain.model.Account
import top.aico.wxcacheviewer.domain.model.CacheFile
import top.aico.wxcacheviewer.domain.model.SortDirection
import top.aico.wxcacheviewer.domain.model.SortField
import top.aico.wxcacheviewer.domain.model.SortOption
import top.aico.wxcacheviewer.ui.account.AccountSwitchDialog
import top.aico.wxcacheviewer.ui.common.ErrorView
import top.aico.wxcacheviewer.ui.common.LoadingView
import top.aico.wxcacheviewer.ui.common.formatFileSize
import top.aico.wxcacheviewer.ui.common.formatModifiedAt
import top.aico.wxcacheviewer.ui.common.shortHash
import top.aico.wxcacheviewer.viewmodel.GalleryUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    state: GalleryUiState,
    accountHash: String,
    remark: String,
    accounts: List<Account>,
    remarks: Map<String, String>,
    canSwitchAccount: Boolean,
    onSetGridMode: (GridMode) -> Unit,
    onSetSortOption: (SortOption) -> Unit,
    onSwitchAccount: (String) -> Unit,
    onSaveRemark: (String, String) -> Unit,
    onRetry: () -> Unit
) {
    val context = LocalContext.current
    val thumbnailLoader = remember { ThumbnailLoader(context, RootCommandExecutor()) }
    LaunchedEffect(accountHash) {
        thumbnailLoader.clear()
    }

    var showSwitchDialog by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }

    val gridState = rememberLazyGridState()
    LaunchedEffect(state.files) {
        if (state.files.isNotEmpty()) {
            gridState.scrollToItem(0)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    AccountTitle(
                        hash = accountHash,
                        remark = remark,
                        canSwitch = canSwitchAccount,
                        onClick = { showSwitchDialog = true }
                    )
                },
                actions = {
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_sort),
                                contentDescription = stringResource(id = R.string.sort),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        SortMenu(
                            expanded = showSortMenu,
                            option = state.sortOption,
                            onSelect = {
                                onSetSortOption(it)
                                showSortMenu = false
                            },
                            onDismiss = { showSortMenu = false }
                        )
                    }

                    val isListView = state.gridMode == GridMode.ONE
                    val nextMode = if (isListView) GridMode.TWO else GridMode.ONE
                    IconButton(onClick = { onSetGridMode(nextMode) }) {
                        Icon(
                            painter = painterResource(
                                id = if (isListView) R.drawable.ic_view_list else R.drawable.ic_view_grid
                            ),
                            contentDescription = stringResource(
                                id = if (isListView) R.string.view_mode_list else R.string.view_mode_grid
                            ),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
                state = gridState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items = state.files, key = { it.name }) { file ->
                    CacheFileTile(file = file, thumbnailLoader = thumbnailLoader)
                }
            }
        }
    }

    if (showSwitchDialog) {
        AccountSwitchDialog(
            accounts = accounts,
            remarks = remarks,
            currentHash = accountHash,
            onSelect = {
                onSwitchAccount(it)
                showSwitchDialog = false
            },
            onSaveRemark = onSaveRemark,
            onDismiss = { showSwitchDialog = false }
        )
    }
}

@Composable
private fun SortMenu(
    expanded: Boolean,
    option: SortOption,
    onSelect: (SortOption) -> Unit,
    onDismiss: () -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        MenuSectionLabel(text = stringResource(id = R.string.sort_field_title))
        SortField.entries.forEach { field ->
            DropdownMenuItem(
                text = { Text(text = stringResource(id = field.labelRes())) },
                trailingIcon = {
                    RadioButton(selected = option.field == field, onClick = null)
                },
                onClick = { onSelect(option.copy(field = field)) }
            )
        }
        MenuSectionLabel(text = stringResource(id = R.string.sort_direction_title))
        SortDirection.entries.forEach { direction ->
            DropdownMenuItem(
                text = { Text(text = stringResource(id = direction.labelRes())) },
                trailingIcon = {
                    RadioButton(selected = option.direction == direction, onClick = null)
                },
                onClick = { onSelect(option.copy(direction = direction)) }
            )
        }
    }
}

@Composable
private fun MenuSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)
    )
}

private fun SortField.labelRes(): Int = when (this) {
    SortField.SIZE -> R.string.sort_field_size
    SortField.MODIFIED_TIME -> R.string.sort_field_time
}

private fun SortDirection.labelRes(): Int = when (this) {
    SortDirection.ASC -> R.string.sort_direction_asc
    SortDirection.DESC -> R.string.sort_direction_desc
}

@Composable
private fun AccountTitle(
    hash: String,
    remark: String,
    canSwitch: Boolean,
    onClick: () -> Unit
) {
    val short = shortHash(hash)
    val hasRemark = remark.isNotBlank()
    val chevronTint = if (canSwitch) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = canSwitch, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Column {
            Text(
                text = if (hasRemark) remark else short,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (hasRemark) {
                Text(
                    text = short,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
        Icon(
            imageVector = Icons.Filled.ArrowDropDown,
            contentDescription = stringResource(id = R.string.switch_account),
            tint = chevronTint
        )
    }
}

@Composable
private fun CacheFileTile(file: CacheFile, thumbnailLoader: ThumbnailLoader) {
    val bitmap by produceState<Bitmap?>(
        initialValue = null,
        key1 = file.accountHash,
        key2 = file.name
    ) {
        value = thumbnailLoader.load(file.accountHash, file)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            val previewModifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
            if (bitmap != null) {
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = null,
                    modifier = previewModifier,
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = previewModifier.background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = file.type.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val modifiedLabel = formatModifiedAt(file.modifiedAt)
                Text(
                    text = buildString {
                        append(file.type.name)
                        append(" / ")
                        append(formatFileSize(file.size))
                        if (modifiedLabel.isNotEmpty()) {
                            append(" / ")
                            append(modifiedLabel)
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
