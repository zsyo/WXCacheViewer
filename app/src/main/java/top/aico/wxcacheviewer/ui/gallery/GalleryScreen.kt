package top.aico.wxcacheviewer.ui.gallery

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.first
import top.aico.wxcacheviewer.R
import top.aico.wxcacheviewer.data.image.CoilImageLoaders
import top.aico.wxcacheviewer.data.media.MediaPreparer
import top.aico.wxcacheviewer.data.repository.GridMode
import top.aico.wxcacheviewer.data.root.RootCommandExecutor
import top.aico.wxcacheviewer.data.thumbnail.ThumbnailLoader
import top.aico.wxcacheviewer.domain.model.Account
import top.aico.wxcacheviewer.domain.model.CacheFile
import top.aico.wxcacheviewer.domain.model.FileType
import top.aico.wxcacheviewer.domain.model.SortDirection
import top.aico.wxcacheviewer.domain.model.SortField
import top.aico.wxcacheviewer.domain.model.SortOption
import top.aico.wxcacheviewer.domain.source.SourceTab
import top.aico.wxcacheviewer.ui.account.AccountSwitchDialog
import top.aico.wxcacheviewer.ui.common.ErrorView
import top.aico.wxcacheviewer.ui.common.LoadingView
import top.aico.wxcacheviewer.ui.common.formatFileSize
import top.aico.wxcacheviewer.ui.common.formatModifiedAt
import top.aico.wxcacheviewer.ui.common.shortHash
import top.aico.wxcacheviewer.ui.player.PlayerScreen
import top.aico.wxcacheviewer.ui.viewer.ImageViewerScreen
import top.aico.wxcacheviewer.viewmodel.BatchAction
import top.aico.wxcacheviewer.viewmodel.BatchProgress
import top.aico.wxcacheviewer.viewmodel.GalleryUiState
import java.io.File

/** 倒数第几项进入可视区域就开始取下一页，留一点提前量避免等到底。 */
private const val LOAD_MORE_THRESHOLD = 6

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(
    state: GalleryUiState,
    accountHash: String,
    remark: String,
    autoName: String,
    accounts: List<Account>,
    remarks: Map<String, String>,
    autoNames: Map<String, String>,
    canSwitchAccount: Boolean,
    onSelectTab: (SourceTab) -> Unit,
    onSetGridMode: (GridMode) -> Unit,
    onSetSortOption: (SortOption) -> Unit,
    onSwitchAccount: (String) -> Unit,
    onSaveRemark: (String, String) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onStartSelection: (CacheFile) -> Unit,
    onToggleSelection: (CacheFile) -> Unit,
    onToggleSelectAll: () -> Unit,
    onInvertSelection: () -> Unit,
    onExitSelection: () -> Unit,
    onExportSelected: () -> Unit,
    onDeleteSelected: () -> Unit,
    onNoticeShown: () -> Unit,
    onSaveOne: (CacheFile, (Boolean, String) -> Unit) -> Unit,
    onRetry: () -> Unit
) {
    val context = LocalContext.current
    val thumbnailLoader = remember { ThumbnailLoader(context, RootCommandExecutor()) }
    val mediaPreparer = remember { MediaPreparer(context, RootCommandExecutor()) }
    LaunchedEffect(accountHash) {
        thumbnailLoader.clear()
    }

    var showSwitchDialog by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var playingFile by remember { mutableStateOf<CacheFile?>(null) }
    var previewKey by remember { mutableStateOf<String?>(null) }

    val gridState = rememberLazyGridState()
    LaunchedEffect(state.resetScrollToken) {
        if (state.files.isNotEmpty()) {
            gridState.scrollToItem(0)
        }
    }

    // 滚到快结尾时自动取下一页；files.size 变化后重新监听，形成「滚到底→加载→再滚」的循环。
    LaunchedEffect(state.hasMore, state.loadingMore, state.files.size) {
        if (!state.hasMore || state.loadingMore) return@LaunchedEffect
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .first { it >= state.files.size - LOAD_MORE_THRESHOLD }
        onLoadMore()
    }

    BackHandler(enabled = state.selectionMode) { onExitSelection() }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.error, state.files.isEmpty()) {
        val message = state.error
        if (message != null && state.files.isNotEmpty()) {
            snackbarHostState.showSnackbar(message)
        }
    }
    LaunchedEffect(state.notice) {
        val notice = state.notice
        if (notice != null) {
            snackbarHostState.showSnackbar(notice)
            onNoticeShown()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
            TopAppBar(
                title = {
                    if (state.selectionMode) {
                        Text(
                            text = stringResource(
                                id = R.string.selection_count,
                                state.selectedKeys.size
                            ),
                            style = MaterialTheme.typography.titleMedium
                        )
                    } else {
                        AccountTitle(
                            hash = accountHash,
                            remark = remark,
                            autoName = autoName,
                            canSwitch = canSwitchAccount,
                            onClick = { showSwitchDialog = true }
                        )
                    }
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
                GalleryTabs(
                    selected = state.tab,
                    enabled = !state.selectionMode,
                    onSelect = onSelectTab
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize()
            ) {
                when {
                    state.loading -> LoadingView(text = stringResource(id = R.string.loading_files))

                    state.files.isEmpty() && state.error != null ->
                        ErrorView(message = state.error, onRetry = onRetry)

                    state.files.isEmpty() -> ErrorView(
                        message = stringResource(id = R.string.no_cache_files),
                        onRetry = onRetry
                    )

                    else -> LazyVerticalGrid(
                        columns = GridCells.Fixed(if (state.gridMode == GridMode.TWO) 2 else 1),
                        state = gridState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 8.dp,
                            top = 8.dp,
                            end = 8.dp,
                            bottom = if (state.selectionMode) 104.dp else 8.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(items = state.files, key = { it.key }) { file ->
                            val selected = file.key in state.selectedKeys
                            CacheFileTile(
                                file = file,
                                thumbnailLoader = thumbnailLoader,
                                preparer = mediaPreparer,
                                selectionMode = state.selectionMode,
                                selected = selected,
                                onClick = {
                                    when {
                                        state.selectionMode -> onToggleSelection(file)
                                        file.type == FileType.MP4 -> playingFile = file
                                        else -> previewKey = file.key
                                    }
                                },
                                onLongClick = {
                                    if (state.selectionMode) {
                                        onToggleSelection(file)
                                    } else {
                                        onStartSelection(file)
                                    }
                                }
                            )
                        }
                        if (state.hasMore) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                LoadMoreFooter(
                                    loading = state.loadingMore,
                                    onLoadMore = onLoadMore
                                )
                            }
                        }
                    }
                }
            }

            if (state.selectionMode) {
                SelectionToolbar(
                    allSelected = state.allSelected,
                    enabled = !state.actionBusy,
                    progress = state.batchProgress,
                    showDelete = state.canDelete,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 20.dp),
                    onToggleSelectAll = onToggleSelectAll,
                    onInvertSelection = onInvertSelection,
                    onExport = onExportSelected,
                    onDelete = { showDeleteConfirm = true },
                    onExit = onExitSelection
                )
            }
        }
    }

    playingFile?.let { playing ->
        PlayerScreen(file = playing, onClose = { playingFile = null })
    }

    val previewFiles = state.files.filter { it.type != FileType.MP4 }
    previewKey?.let { key ->
        if (previewFiles.isNotEmpty()) {
            ImageViewerScreen(
                files = previewFiles,
                initialKey = key,
                preparer = mediaPreparer,
                onRequestSave = onSaveOne,
                onClose = { previewKey = null }
            )
        }
    }

    if (showSwitchDialog) {
        AccountSwitchDialog(
            accounts = accounts,
            remarks = remarks,
            autoNames = autoNames,
            currentHash = accountHash,
            onSelect = {
                onSwitchAccount(it)
                showSwitchDialog = false
            },
            onSaveRemark = onSaveRemark,
            onDismiss = { showSwitchDialog = false }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(text = stringResource(id = R.string.delete_selected_title)) },
            text = {
                Text(
                    text = stringResource(
                        id = R.string.delete_selected_message,
                        state.selectedKeys.size
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDeleteSelected()
                    }
                ) {
                    Text(
                        text = stringResource(id = R.string.file_action_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(text = stringResource(id = R.string.cancel))
                }
            }
        )
    }
}

/**
 * 多选模式下的悬浮工具条：全选/全不选、反选、导出、删除、退出。
 * 半透明圆角背景，浮在列表底部；批量操作执行期间原地切换为进度显示。
 */
@Composable
private fun SelectionToolbar(
    allSelected: Boolean,
    enabled: Boolean,
    progress: BatchProgress?,
    showDelete: Boolean,
    modifier: Modifier = Modifier,
    onToggleSelectAll: () -> Unit,
    onInvertSelection: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onExit: () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.94f),
        shadowElevation = 8.dp
    ) {
        if (progress != null) {
            BatchProgressRow(progress = progress)
        } else {
            Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SelectionAction(
                    iconRes = if (allSelected) R.drawable.ic_deselect_all else R.drawable.ic_select_all,
                    labelRes = if (allSelected) R.string.selection_clear_all else R.string.selection_select_all,
                    enabled = enabled,
                    onClick = onToggleSelectAll
                )
                SelectionAction(
                    iconRes = R.drawable.ic_invert,
                    labelRes = R.string.selection_invert,
                    enabled = enabled,
                    onClick = onInvertSelection
                )
                SelectionAction(
                    iconRes = R.drawable.ic_export,
                    labelRes = R.string.file_action_export,
                    enabled = enabled,
                    onClick = onExport
                )
                if (showDelete) {
                    SelectionAction(
                        iconRes = R.drawable.ic_delete,
                        labelRes = R.string.file_action_delete,
                        enabled = enabled,
                        tint = MaterialTheme.colorScheme.error,
                        onClick = onDelete
                    )
                }
                SelectionAction(
                    iconRes = R.drawable.ic_close,
                    labelRes = R.string.selection_exit,
                    enabled = enabled,
                    onClick = onExit
                )
            }
        }
    }
}

/** 批量操作进度：环形进度 + 「正在导出 12/100」，高度与按钮行接近，避免工具条跳动。 */
@Composable
private fun BatchProgressRow(progress: BatchProgress) {
    val fraction = if (progress.total > 0) {
        progress.done.toFloat() / progress.total.toFloat()
    } else {
        0f
    }
    Row(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CircularProgressIndicator(
            progress = { fraction },
            modifier = Modifier.size(22.dp),
            strokeWidth = 2.5.dp
        )
        Text(
            text = stringResource(
                id = when (progress.action) {
                    BatchAction.EXPORT -> R.string.batch_export_progress
                    BatchAction.DELETE -> R.string.batch_delete_progress
                },
                progress.done,
                progress.total
            ),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 列表底部：还有下一页时显示加载动画；万一自动加载没触发（比如首屏没铺满），
 * 这里还留一个可点的「加载更多」按钮兜底。
 */
@Composable
private fun LoadMoreFooter(loading: Boolean, onLoadMore: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        if (loading) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
                Text(
                    text = stringResource(id = R.string.loading_more),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            TextButton(onClick = onLoadMore) {
                Text(text = stringResource(id = R.string.load_more))
            }
        }
    }
}

@Composable
private fun SelectionAction(
    iconRes: Int,
    labelRes: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = stringResource(id = labelRes),
            tint = if (enabled) tint else tint.copy(alpha = 0.38f)
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
    autoName: String,
    canSwitch: Boolean,
    onClick: () -> Unit
) {
    val short = shortHash(hash)
    val displayName = remark.ifBlank { autoName }
    val hasName = displayName.isNotBlank()
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
                text = if (hasName) displayName else short,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (hasName) {
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CacheFileTile(
    file: CacheFile,
    thumbnailLoader: ThumbnailLoader,
    preparer: MediaPreparer,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val isVideo = file.type == FileType.MP4
    val bitmap by produceState<Bitmap?>(
        initialValue = null,
        key1 = file.key
    ) {
        if (isVideo) value = thumbnailLoader.load(file)
    }
    val previewImage by produceState<File?>(
        initialValue = null,
        key1 = file.key
    ) {
        if (!isVideo) value = preparer.prepare(file)
    }

    val shape = CardDefaults.shape
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (selected) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
                } else {
                    Modifier
                }
            ),
        shape = shape,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box(
            modifier = Modifier.combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
        ) {
            Column {
                val previewModifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(if (isVideo) 16f / 9f else 1f)
                when {
                    bitmap != null -> Image(
                        bitmap = bitmap!!.asImageBitmap(),
                        contentDescription = null,
                        modifier = previewModifier,
                        contentScale = ContentScale.Crop
                    )

                    previewImage != null -> AsyncImage(
                        model = previewImage,
                        imageLoader = CoilImageLoaders.static,
                        contentDescription = null,
                        modifier = previewModifier,
                        contentScale = ContentScale.Crop
                    )

                    else -> Box(
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

            if (selectionMode) {
                SelectionBadge(
                    selected = selected,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                )
            }
        }
    }
}

@Composable
private fun SelectionBadge(
    selected: Boolean,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(if (selected) primary else Color.Black.copy(alpha = 0.32f))
            .border(1.5.dp, Color.White, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

/** 顶部缓存类型切换；多选时禁用，避免切换后选中状态落到别的列表上。 */
@Composable
private fun GalleryTabs(
    selected: SourceTab,
    enabled: Boolean,
    onSelect: (SourceTab) -> Unit
) {
    TabRow(selectedTabIndex = SourceTab.entries.indexOf(selected)) {
        SourceTab.entries.forEach { tab ->
            Tab(
                selected = tab == selected,
                enabled = enabled,
                onClick = { if (tab != selected) onSelect(tab) },
                text = { Text(text = tab.title, maxLines = 1) }
            )
        }
    }
}
