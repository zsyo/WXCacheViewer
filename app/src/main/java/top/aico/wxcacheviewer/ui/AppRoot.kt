package top.aico.wxcacheviewer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import top.aico.wxcacheviewer.domain.source.SourceTab
import top.aico.wxcacheviewer.ui.account.AccountSelectScreen
import top.aico.wxcacheviewer.ui.gallery.GalleryScreen
import top.aico.wxcacheviewer.ui.settings.SettingsScreen
import top.aico.wxcacheviewer.viewmodel.AccountViewModel
import top.aico.wxcacheviewer.viewmodel.GalleryViewModel
import top.aico.wxcacheviewer.viewmodel.SettingsViewModel

@Composable
fun AppRoot(
    selectedAccountHash: String,
    onSelectedAccountChanged: (String) -> Unit
) {
    val accountViewModel: AccountViewModel = viewModel()
    val accountState by accountViewModel.uiState.collectAsState()
    // 设置页覆盖在相册之上，返回时保留原有列表与滚动位置
    val settingsViewModel: SettingsViewModel = viewModel()
    val settingsState by settingsViewModel.uiState.collectAsState()

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
            var selectedTabId by rememberSaveable { mutableStateOf(SourceTab.FINDER.id) }
            var showSettings by rememberSaveable { mutableStateOf(false) }
            val tab = SourceTab.fromId(selectedTabId)
            // 每个标签页一个 ViewModel，切换标签天然隔离列表、多选与滚动状态。
            val galleryViewModel: GalleryViewModel =
                viewModel(key = "$selectedAccountHash:${tab.id}")
            val galleryState by galleryViewModel.uiState.collectAsState()

            LaunchedEffect(selectedAccountHash, tab.id) {
                galleryViewModel.load(selectedAccountHash, tab)
            }

            val remark = accountState.remarks[selectedAccountHash].orEmpty()
            Box(modifier = Modifier.fillMaxSize()) {
                GalleryScreen(
                    state = galleryState,
                    accountHash = selectedAccountHash,
                    remark = remark,
                    autoName = accountState.autoNames[selectedAccountHash].orEmpty(),
                    accounts = accountState.accounts,
                    remarks = accountState.remarks,
                    autoNames = accountState.autoNames,
                    canSwitchAccount = accountState.accounts.size > 1,
                    onSelectTab = { selectedTabId = it.id },
                    onSetGridMode = galleryViewModel::setGridMode,
                    onSetSortOption = galleryViewModel::setSortOption,
                    onSwitchAccount = { onSelectedAccountChanged(it) },
                    onSaveRemark = accountViewModel::saveRemark,
                    onRefresh = { galleryViewModel.refresh(selectedAccountHash) },
                    onLoadMore = { galleryViewModel.loadMore(selectedAccountHash) },
                    onStartSelection = { galleryViewModel.startSelection(it.key) },
                    onToggleSelection = { galleryViewModel.toggleSelection(it.key) },
                    onToggleSelectAll = galleryViewModel::toggleSelectAll,
                    onInvertSelection = galleryViewModel::invertSelection,
                    onExitSelection = galleryViewModel::exitSelection,
                    onExportSelected = galleryViewModel::exportSelected,
                    onDeleteSelected = galleryViewModel::deleteSelected,
                    onNoticeShown = galleryViewModel::consumeNotice,
                    onDismissFinderTip = galleryViewModel::dismissFinderTip,
                    onSaveOne = { file, onResult -> galleryViewModel.exportOne(file, onResult) },
                    onRetry = { galleryViewModel.load(selectedAccountHash, tab) },
                    onOpenSettings = { showSettings = true }
                )

                if (showSettings) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        SettingsScreen(
                            state = settingsState,
                            onBack = { showSettings = false },
                            onExportRootChosen = settingsViewModel::setExportRoot,
                            onResetExportRoot = settingsViewModel::resetExportRoot,
                            onRefreshCacheSize = settingsViewModel::refreshCacheSize,
                            onClearCache = settingsViewModel::clearCache,
                            onCheckUpdate = settingsViewModel::checkForUpdate,
                            onDismissUpdate = settingsViewModel::consumeUpdateRelease,
                            onSetUpdateChannel = settingsViewModel::setUpdateChannel,
                            onNoticeShown = settingsViewModel::consumeNotice
                        )
                    }
                }
            }
        }
    }
}
